package com.payment.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentCommand.Line;
import com.payment.payment.application.port.out.CompensationFailureRecorder;
import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.domain.PaymentMethodType;
import java.time.Clock;
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
}
