package com.payment.payment.application;

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
import com.payment.payment.application.port.out.CompensationFailureRecorder;
import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PaymentMethodType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 트랜잭션 경계 (설계 6-1). 오케스트레이터({@link ProcessPaymentService})가 PG 호출을 사이에 끼고
 * 본 협력자의 트랜잭션 메서드들을 차례로 호출한다 — PG 호출이 트랜잭션 밖이라 단일 {@code @Transactional}로
 * 묶지 않는다.
 *
 * <p>{@code reserve}는 트랜잭션 1로, 잔액→한도 고정 순서 비관적 락(설계 9-2)을 잡고 한도 누적·잔액 차감을
 * 원자적으로 처리한 뒤 결제·멱등성 행을 PENDING으로 남긴다. 잔액·한도는 balance 컨텍스트 도메인의 규칙으로
 * 검증한다(부족/초과 시 예외).
 */
@Service
@RequiredArgsConstructor
public class PaymentTransactionService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final BalanceRepository balanceRepo;
    private final DailyUsageRepository usageRepo;
    private final DailyLimitRepository limitRepo;
    private final BalanceHistoryRepository historyRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentIdempotencyRepository idempotencyRepo;
    private final CompensationFailureRecorder compensationRecorder;
    private final Clock clock;

    public record Reservation(String paymentId, String idempotencyKey, String pgIdempotencyKey,
            List<PaymentAllocation> externalAllocations) {}

    @Transactional
    public Reservation reserve(ProcessPaymentCommand command) {
        LocalDate today = LocalDate.now(clock);
        Payment payment = toPayment(command);

        // 고정 순서 락: 잔액 → 한도 (설계 9-2, 데드락 회피). 신규 사용자는 0으로 보정.
        UserBalance balance = balanceRepo.findByUserIdWithPessimisticLock(command.userId())
                .orElseGet(() -> UserBalance.create(command.userId()));
        UserDailyUsage usage = usageRepo.findByUserIdWithPessimisticLock(command.userId())
                .orElseGet(() -> UserDailyUsage.create(command.userId(), today));
        UserDailyLimit limit = limitRepo.findByUserId(command.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "사용자 한도 설정이 없습니다"));

        // 한도는 결제 전액 누적(설계 0장/시나리오 19), 잔액은 BALANCE 분배 금액만 차감.
        usage.use(command.totalAmount(), limit, today);
        Money balanceAmount = payment.balanceAmount();
        if (balanceAmount.isPositive()) {
            balance.deduct(balanceAmount);
            balanceRepo.save(balance);
            historyRepo.append(BalanceHistory.payment(command.userId(), balanceAmount, balance.balance(), payment.id()));
        }
        usageRepo.save(usage);

        String pgIdempotencyKey = UUID.randomUUID().toString();
        idempotencyRepo.save(PaymentIdempotency.start(
                command.idempotencyKey(), command.userId(), command.orderId(), payment.id(),
                clock.instant().plus(IDEMPOTENCY_TTL)));
        paymentRepo.save(payment);

        return new Reservation(payment.id(), command.idempotencyKey(), pgIdempotencyKey, payment.externalAllocations());
    }

    private Payment toPayment(ProcessPaymentCommand command) {
        List<PaymentAllocation> allocations = command.allocations().stream()
                .map(line -> line.methodType() == PaymentMethodType.BALANCE
                        ? PaymentAllocation.balance(line.amount())
                        : PaymentAllocation.external(line.methodType(), line.methodId(), line.amount()))
                .toList();
        return Payment.create(command.userId(), command.orderId(), command.totalAmount(), allocations);
    }
}
