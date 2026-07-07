package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payment.balance.application.port.out.BalanceHistoryRepository;
import com.payment.balance.application.port.out.BalanceRepository;
import com.payment.balance.application.port.out.DailyLimitRepository;
import com.payment.balance.application.port.out.DailyUsageRepository;
import com.payment.balance.domain.BalanceHistory;
import com.payment.balance.domain.UserBalance;
import com.payment.balance.domain.UserDailyLimit;
import com.payment.balance.domain.UserDailyUsage;
import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentCommand.Line;
import com.payment.payment.application.port.out.CompensationFailureRecorder;
import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.IdempotencyStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgCallStatus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PaymentTransactionServiceTest {

    private final BalanceRepository balanceRepo = mock(BalanceRepository.class);
    private final DailyUsageRepository usageRepo = mock(DailyUsageRepository.class);
    private final DailyLimitRepository limitRepo = mock(DailyLimitRepository.class);
    private final BalanceHistoryRepository historyRepo = mock(BalanceHistoryRepository.class);
    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);
    private final PaymentIdempotencyRepository idempotencyRepo = mock(PaymentIdempotencyRepository.class);
    private final CompensationFailureRecorder compensationRecorder = mock(CompensationFailureRecorder.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-25T00:00:00Z"), ZoneOffset.UTC);

    private final PaymentTransactionService service = new PaymentTransactionService(
            balanceRepo, usageRepo, limitRepo, historyRepo, paymentRepo, idempotencyRepo, compensationRecorder, clock);

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 25);

    private ProcessPaymentCommand splitCommand() {
        return new ProcessPaymentCommand("idem-1", "u1", "ord1", Money.won(1_000_000),
                List.of(new Line(PaymentMethodType.BALANCE, null, Money.won(300_000)),
                        new Line(PaymentMethodType.CARD, "c1", Money.won(700_000))));
    }

    @BeforeEach
    void stubs() {
        when(limitRepo.findByUserId("u1")).thenReturn(Optional.of(new UserDailyLimit("u1", Money.won(5_000_000))));
        when(usageRepo.findByUserIdWithPessimisticLock("u1"))
                .thenReturn(Optional.of(UserDailyUsage.create("u1", TODAY)));
        when(balanceRepo.findByUserIdWithPessimisticLock("u1"))
                .thenReturn(Optional.of(new UserBalance("u1", Money.won(1_000_000))));
    }

    @Test
    @DisplayName("정상: 잔액 차감 + 한도 누적(전액) + history 기록 + payment/idempotency 저장")
    void reserve_정상() {
        PaymentTransactionService.Reservation reservation = service.reserve(splitCommand());

        ArgumentCaptor<UserBalance> balanceCaptor = ArgumentCaptor.forClass(UserBalance.class);
        verify(balanceRepo).save(balanceCaptor.capture());
        assertThat(balanceCaptor.getValue().balance()).isEqualTo(Money.won(700_000)); // 100만 - 30만

        ArgumentCaptor<UserDailyUsage> usageCaptor = ArgumentCaptor.forClass(UserDailyUsage.class);
        verify(usageRepo).save(usageCaptor.capture());
        assertThat(usageCaptor.getValue().usedAmount()).isEqualTo(Money.won(1_000_000)); // 전액 누적

        verify(historyRepo).append(any(BalanceHistory.class));
        verify(paymentRepo).save(any());
        verify(idempotencyRepo).save(any());
        assertThat(reservation.externalAllocations()).hasSize(1);
        assertThat(reservation.pgIdempotencyKey()).isNotBlank();
    }

    @Test
    @DisplayName("잔액 부족이면 INSUFFICIENT_BALANCE")
    void reserve_잔액부족() {
        when(balanceRepo.findByUserIdWithPessimisticLock("u1"))
                .thenReturn(Optional.of(new UserBalance("u1", Money.won(100_000))));

        assertThatThrownBy(() -> service.reserve(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("일일 한도 초과면 DAILY_LIMIT_EXCEEDED")
    void reserve_한도초과() {
        when(limitRepo.findByUserId("u1")).thenReturn(Optional.of(new UserDailyLimit("u1", Money.won(500_000))));

        assertThatThrownBy(() -> service.reserve(splitCommand()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("카드 단독 결제는 잔액·history를 건드리지 않고 한도만 누적한다")
    void reserve_카드단독() {
        ProcessPaymentCommand cardOnly = new ProcessPaymentCommand("idem-2", "u1", "ord2", Money.won(700_000),
                List.of(new Line(PaymentMethodType.CARD, "c1", Money.won(700_000))));

        PaymentTransactionService.Reservation reservation = service.reserve(cardOnly);

        verify(balanceRepo, never()).save(any());
        verify(historyRepo, never()).append(any());
        verify(usageRepo).save(any());
        assertThat(reservation.externalAllocations()).hasSize(1);
    }

    @Test
    @DisplayName("confirm: 외부 allocation SETTLED(+pgTxId) + payment PAID + idempotency COMPLETED")
    void confirm_paid() {
        Payment payment = splitPayment();
        PaymentAllocation card = payment.externalAllocations().getFirst();
        when(paymentRepo.findById(payment.id())).thenReturn(Optional.of(payment));
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotencyFor(payment)));

        PaymentResult result = service.confirm(payment.id(), "u1", "idem-1",
                List.of(new PaymentTransactionService.Charged(card.id(), "PG_tx_1")));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        PaymentResult.Alloc cardResult = result.allocations().stream()
                .filter(a -> a.methodType() == PaymentMethodType.CARD).findFirst().orElseThrow();
        assertThat(cardResult.status()).isEqualTo(AllocationStatus.SETTLED);
        assertThat(cardResult.pgTransactionId()).isEqualTo("PG_tx_1");
        verify(paymentRepo).save(argThat(saved -> saved.status() == PaymentStatus.PAID));
        verify(idempotencyRepo).save(argThat(i -> i.status() == IdempotencyStatus.COMPLETED));
    }

    @Test
    @DisplayName("confirm: 외부 allocation이 charged 목록에 없으면 INCONSISTENT_STATE (PG 청구 없이 정산 방지)")
    void confirm_외부_미청구_거부() {
        Payment payment = splitPayment();
        when(paymentRepo.findById(payment.id())).thenReturn(Optional.of(payment));
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotencyFor(payment)));

        assertThatThrownBy(() -> service.confirm(payment.id(), "u1", "idem-1", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INCONSISTENT_STATE));
    }

    @Test
    @DisplayName("confirm: 외부 allocation의 pg_transaction_id가 null이면 INCONSISTENT_STATE")
    void confirm_pgTxId_null_거부() {
        Payment payment = splitPayment();
        PaymentAllocation card = payment.externalAllocations().getFirst();
        when(paymentRepo.findById(payment.id())).thenReturn(Optional.of(payment));
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotencyFor(payment)));

        assertThatThrownBy(() -> service.confirm(payment.id(), "u1", "idem-1",
                List.of(new PaymentTransactionService.Charged(card.id(), null))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INCONSISTENT_STATE));
    }

    @Test
    @DisplayName("compensate: 잔액(BALANCE 분배분)과 일일 누적(전액)을 원복한다")
    void compensate_rolls_back_balance_and_usage() {
        Payment payment = compensateScenario();

        service.compensate(payment.id(), "u1", "idem-1", PaymentStatus.FAILED, List.of());

        verify(balanceRepo).save(argThat(b -> b.balance().equals(Money.won(1_000_000)))); // 70만 + 30만
        verify(historyRepo).append(any(BalanceHistory.class));
        ArgumentCaptor<UserDailyUsage> usageCaptor = ArgumentCaptor.forClass(UserDailyUsage.class);
        verify(usageRepo).save(usageCaptor.capture());
        assertThat(usageCaptor.getValue().usedAmount()).isEqualTo(Money.ZERO); // 전액 원복
    }

    @Test
    @DisplayName("compensate: 이미 청구된 카드 금액을 PG_REFUND_CALL로 기록한다(회수 보류)")
    void compensate_records_pg_refund_for_charged_card() {
        Payment payment = compensateScenario();
        PaymentAllocation card = payment.externalAllocations().getFirst();

        service.compensate(payment.id(), "u1", "idem-1", PaymentStatus.FAILED,
                List.of(new PaymentTransactionService.Charged(card.id(), "PG_tx_1")));

        verify(compensationRecorder).record(eq(payment.id()),
                eq(CompensationFailureRecorder.FailureType.PG_REFUND_CALL), eq(Money.won(700_000)));
    }

    @Test
    @DisplayName("compensate(FAILED): payment와 idempotency를 FAILED로 전이한다")
    void compensate_marks_failed() {
        Payment payment = compensateScenario();

        service.compensate(payment.id(), "u1", "idem-1", PaymentStatus.FAILED, List.of());

        verify(paymentRepo).save(argThat(saved -> saved.status() == PaymentStatus.FAILED));
        verify(idempotencyRepo).save(argThat(i -> i.status() == IdempotencyStatus.FAILED));
    }

    @Test
    @DisplayName("compensate(FAILED_REFUNDED, 청구분 없음): payment FAILED_REFUNDED + PG 회수 기록 없음")
    void compensate_failed_refunded_without_charge() {
        Payment payment = compensateScenario();

        service.compensate(payment.id(), "u1", "idem-1", PaymentStatus.FAILED_REFUNDED, List.of());

        verify(paymentRepo).save(argThat(saved -> saved.status() == PaymentStatus.FAILED_REFUNDED));
        verify(compensationRecorder, never()).record(any(), any(), any());
    }

    @Test
    @DisplayName("markPgCalling: pgCallStatus를 CALLING으로 전이하고 pg_idempotency_key를 보존 후 저장")
    void markPgCalling_transitions() {
        PaymentIdempotency idempotency = idempotencyFor(splitPayment());
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotency));

        service.markPgCalling("u1", "idem-1", "pg-1");

        assertThat(idempotency.pgCallStatus()).isEqualTo(PgCallStatus.CALLING);
        assertThat(idempotency.pgIdempotencyKey()).isEqualTo("pg-1");
        verify(idempotencyRepo).save(idempotency);
    }

    @Test
    @DisplayName("markPgStatus: 주어진 PG 호출 상태(UNKNOWN)로 전이 후 저장")
    void markPgStatus_transitions() {
        PaymentIdempotency idempotency = idempotencyFor(splitPayment());
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotency));

        service.markPgStatus("u1", "idem-1", PgCallStatus.UNKNOWN);

        assertThat(idempotency.pgCallStatus()).isEqualTo(PgCallStatus.UNKNOWN);
        verify(idempotencyRepo).save(idempotency);
    }

    @Test
    @DisplayName("confirm: payment 행이 없으면 INCONSISTENT_STATE (NoSuchElement 누출 방지)")
    void confirm_payment_없음() {
        when(paymentRepo.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm("missing", "u1", "idem-1", List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INCONSISTENT_STATE));
    }

    @Test
    @DisplayName("markPgStatus: 멱등성 행이 없으면 INCONSISTENT_STATE")
    void markPgStatus_멱등성_없음() {
        when(idempotencyRepo.find("u1", "missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.markPgStatus("u1", "missing", PgCallStatus.UNKNOWN))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INCONSISTENT_STATE));
    }

    /** compensate 대상 분할 결제 + 원복용 잔액(70만)·누적(전액 100만) 스텁. */
    private Payment compensateScenario() {
        Payment payment = splitPayment();
        when(paymentRepo.findById(payment.id())).thenReturn(Optional.of(payment));
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(idempotencyFor(payment)));
        when(balanceRepo.findByUserIdWithPessimisticLock("u1"))
                .thenReturn(Optional.of(new UserBalance("u1", Money.won(700_000))));
        when(usageRepo.findByUserIdWithPessimisticLock("u1"))
                .thenReturn(Optional.of(new UserDailyUsage("u1", Money.won(1_000_000), TODAY)));
        return payment;
    }

    private Payment splitPayment() {
        return Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(PaymentAllocation.balance(Money.won(300_000)),
                        PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000))));
    }

    private PaymentIdempotency idempotencyFor(Payment payment) {
        return PaymentIdempotency.start("idem-1", "u1", "ord1", payment.id(),
                clock.instant().plus(Duration.ofHours(24)));
    }
}
