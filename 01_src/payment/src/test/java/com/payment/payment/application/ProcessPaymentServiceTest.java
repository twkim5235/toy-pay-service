package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.PaymentTransactionService.Charged;
import com.payment.payment.application.PaymentTransactionService.Reservation;
import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentCommand.Line;
import com.payment.payment.application.port.out.PaymentCreatedEvent;
import com.payment.payment.application.port.out.PaymentEventPublisher;
import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.application.port.out.PgPort;
import com.payment.payment.application.port.out.PgPort.PgChargeRequest;
import com.payment.payment.application.port.out.PgPort.PgChargeResult;
import com.payment.payment.domain.IdempotencyStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgCallStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.PessimisticLockingFailureException;

class ProcessPaymentServiceTest {

    private final PaymentTransactionService tx = mock(PaymentTransactionService.class);
    private final PgPort pgPort = mock(PgPort.class);
    private final PaymentIdempotencyRepository idempotencyRepo = mock(PaymentIdempotencyRepository.class);
    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    private final PaymentEventPublisher publisher = mock(PaymentEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private final ProcessPaymentService service = new ProcessPaymentService(
            tx, pgPort, idempotencyRepo, paymentRepo, publisher, clock);

    private ProcessPaymentCommand splitCommand() {
        return new ProcessPaymentCommand("idem-1", "u1", "ord1", Money.won(1_000_000),
                List.of(new Line(PaymentMethodType.BALANCE, null, Money.won(300_000)),
                        new Line(PaymentMethodType.CARD, "c1", Money.won(700_000))));
    }

    private PaymentResult paidResult() {
        return new PaymentResult("PAY1", PaymentStatus.PAID, "u1", "ord1",
                Money.won(1_000_000), Instant.parse("2026-07-08T00:00:00Z"), List.of());
    }

    @BeforeEach
    void newRequest() {
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("정상: reserve→PG 승인→SUCCESS 마킹→confirm→이벤트 발행, PAID 반환")
    void happyPath() {
        PaymentAllocation ext = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.approved("PG_tx_1"));
        when(tx.confirm(eq("PAY1"), eq("u1"), eq("idem-1"), anyList())).thenReturn(paidResult());

        PaymentResult result = service.process(splitCommand());

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(tx).markPgCalling("u1", "idem-1", "pg-1");
        verify(tx).markPgStatus("u1", "idem-1", PgCallStatus.SUCCESS);
        verify(tx).confirm("PAY1", "u1", "idem-1", List.of(new Charged(ext.id(), "PG_tx_1")));

        ArgumentCaptor<PaymentCreatedEvent> event = ArgumentCaptor.forClass(PaymentCreatedEvent.class);
        verify(publisher).paymentCreated(event.capture());
        assertThat(event.getValue().paymentId()).isEqualTo("PAY1");
        assertThat(event.getValue().userId()).isEqualTo("u1");
        assertThat(event.getValue().orderId()).isEqualTo("ord1");
        assertThat(event.getValue().totalAmount()).isEqualTo(1_000_000L);
        assertThat(event.getValue().occurredAt()).isEqualTo(Instant.parse("2026-07-08T00:00:00Z"));
    }

    @Test
    @DisplayName("PG 거절(첫 청구): FAILED 마킹 + 보상(FAILED, 회수목록 없음) + PG_DECLINED, 이벤트 미발행")
    void pgDeclined() {
        PaymentAllocation ext = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.declined("도난카드"));

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PG_DECLINED));

        verify(tx).markPgStatus("u1", "idem-1", PgCallStatus.FAILED);
        verify(tx).compensate(eq("PAY1"), eq("u1"), eq("idem-1"), eq(PaymentStatus.FAILED),
                argThat(List::isEmpty));
        verify(tx, never()).confirm(any(), any(), any(), anyList());
        verify(publisher, never()).paymentCreated(any());
    }

    @Test
    @DisplayName("타임아웃: UNKNOWN 마킹 + INTERNAL_ERROR, 원복하지 않는다(보정 배치 회복)")
    void pgTimeoutLeavesStateForRecovery() {
        PaymentAllocation ext = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.timeout());

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR));

        verify(tx).markPgStatus("u1", "idem-1", PgCallStatus.UNKNOWN);
        verify(tx, never()).compensate(any(), any(), any(), any(), anyList());
        verify(publisher, never()).paymentCreated(any());
    }

    @Test
    @DisplayName("멱등 진행중(PENDING): PAYMENT_IN_PROGRESS(409), 재처리 없음")
    void idempotentInProgress() {
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(PaymentIdempotency.restore(
                "idem-1", "u1", "ord1", "PAY1", IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED,
                null, Instant.parse("2026-07-09T00:00:00Z"))));

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));

        verify(tx, never()).reserve(any());
    }

    @Test
    @DisplayName("멱등 완료(COMPLETED): 기존 결제 결과를 반환하고 재처리하지 않는다")
    void idempotentReplay() {
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(PaymentIdempotency.restore(
                "idem-1", "u1", "ord1", "PAY1", IdempotencyStatus.COMPLETED, PgCallStatus.SUCCESS,
                "pg-1", Instant.parse("2026-07-09T00:00:00Z"))));
        Payment paid = Payment.restore("PAY1", "u1", "ord1", Money.won(1_000_000),
                List.of(PaymentAllocation.balance(Money.won(1_000_000))),
                PaymentStatus.PAID, Instant.parse("2026-07-08T00:00:00Z"));
        when(paymentRepo.findById("PAY1")).thenReturn(Optional.of(paid));

        PaymentResult result = service.process(splitCommand());

        assertThat(result.paymentId()).isEqualTo("PAY1");
        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(tx, never()).reserve(any());
        verify(pgPort, never()).charge(any());
    }

    @Test
    @DisplayName("분할 결제: PG 중복 디듀프를 피하도록 외부 allocation마다 파생 키 {base}:{allocationId}로 청구한다")
    void derivesPgIdempotencyKeyPerAllocation() {
        PaymentAllocation card = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(500_000));
        PaymentAllocation account = PaymentAllocation.external(PaymentMethodType.ACCOUNT, "a1", Money.won(200_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(card, account)));
        when(pgPort.charge(any()))
                .thenReturn(PgChargeResult.approved("PG_tx_1"), PgChargeResult.approved("PG_tx_2"));
        when(tx.confirm(eq("PAY1"), eq("u1"), eq("idem-1"), anyList())).thenReturn(paidResult());

        service.process(splitCommand());

        ArgumentCaptor<PgChargeRequest> requests = ArgumentCaptor.forClass(PgChargeRequest.class);
        verify(pgPort, times(2)).charge(requests.capture());
        assertThat(requests.getAllValues()).containsExactly(
                new PgChargeRequest("pg-1:" + card.id(), PaymentMethodType.CARD, "c1", Money.won(500_000)),
                new PgChargeRequest("pg-1:" + account.id(), PaymentMethodType.ACCOUNT, "a1", Money.won(200_000)));
    }

    @Test
    @DisplayName("부분 실패(시나리오 23): 첫 승인 후 둘째 거절 → 승인분을 회수 대상으로 보상에 넘긴다")
    void partialDeclineHandsApprovedChargesToCompensation() {
        PaymentAllocation card = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(500_000));
        PaymentAllocation account = PaymentAllocation.external(PaymentMethodType.ACCOUNT, "a1", Money.won(200_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(card, account)));
        when(pgPort.charge(any()))
                .thenReturn(PgChargeResult.approved("PG_tx_1"), PgChargeResult.declined("잔액부족"));

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PG_DECLINED));

        verify(tx).compensate("PAY1", "u1", "idem-1", PaymentStatus.FAILED,
                List.of(new Charged(card.id(), "PG_tx_1")));
    }

    @Test
    @DisplayName("confirm 실패(시나리오 22): 보상(FAILED_REFUNDED, 청구분 회수) + INCONSISTENT_STATE")
    void confirmFailureCompensatesWithRefund() {
        PaymentAllocation ext = PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.approved("PG_tx_1"));
        when(tx.confirm(any(), any(), any(), anyList())).thenThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INCONSISTENT_STATE));

        verify(tx).compensate("PAY1", "u1", "idem-1", PaymentStatus.FAILED_REFUNDED,
                List.of(new Charged(ext.id(), "PG_tx_1")));
        verify(publisher, never()).paymentCreated(any());
    }

    @Test
    @DisplayName("BALANCE 단독: PG를 호출하지 않고 confirm까지 완료한다")
    void balanceOnlySkipsPg() {
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of()));
        when(tx.confirm(eq("PAY1"), eq("u1"), eq("idem-1"), anyList())).thenReturn(paidResult());

        PaymentResult result = service.process(splitCommand());

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(pgPort, never()).charge(any());
        verify(tx).confirm("PAY1", "u1", "idem-1", List.of());
    }

    @Test
    @DisplayName("멱등 실패(FAILED): 신규 시도로 진행한다")
    void idempotentFailedProceedsAsNew() {
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(PaymentIdempotency.restore(
                "idem-1", "u1", "ord1", "PAY0", IdempotencyStatus.FAILED, PgCallStatus.FAILED,
                "pg-0", Instant.parse("2026-07-09T00:00:00Z"))));
        when(tx.reserve(any())).thenReturn(new Reservation("PAY1", "idem-1", "pg-1", List.of()));
        when(tx.confirm(eq("PAY1"), eq("u1"), eq("idem-1"), anyList())).thenReturn(paidResult());

        PaymentResult result = service.process(splitCommand());

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(tx).reserve(any());
    }

    @Test
    @DisplayName("reserve 락 대기 초과: PAYMENT_IN_PROGRESS(409)로 변환한다(설계 9-4)")
    void lockTimeoutTranslatesTo409() {
        when(tx.reserve(any())).thenThrow(new PessimisticLockingFailureException("lock wait timeout"));

        assertThatThrownBy(() -> service.process(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS));

        verify(pgPort, never()).charge(any());
    }
}
