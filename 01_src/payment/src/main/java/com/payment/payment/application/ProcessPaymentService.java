package com.payment.payment.application;

import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.PaymentTransactionService.Charged;
import com.payment.payment.application.PaymentTransactionService.Reservation;
import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentUseCase;
import com.payment.payment.application.port.out.PaymentCreatedEvent;
import com.payment.payment.application.port.out.PaymentEventPublisher;
import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.application.port.out.PgPort;
import com.payment.payment.application.port.out.PgPort.PgChargeRequest;
import com.payment.payment.application.port.out.PgPort.PgChargeResult;
import com.payment.payment.domain.IdempotencyDecision;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PaymentStatus;
import com.payment.payment.domain.PgCallStatus;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 결제 오케스트레이터 (설계 6-1). 트랜잭션 1({@code reserve}) → PG 청구 → 트랜잭션 2({@code confirm}/
 * {@code compensate})를 지휘한다.
 *
 * <p><b>이 클래스에 {@code @Transactional}을 붙이지 않는다(불변식).</b> PG 호출을 DB 트랜잭션 안에서 하면
 * PG 응답 지연 동안 잔액·한도 락을 쥔 채 대기하게 된다(설계 9-3). 트랜잭션 경계는 전부
 * {@link PaymentTransactionService}에 있다.
 */
@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final PaymentTransactionService tx;
    private final PgPort pgPort;
    private final PaymentIdempotencyRepository idempotencyRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentEventPublisher publisher;
    private final Clock clock;

    @Override
    public PaymentResult process(ProcessPaymentCommand command) {
        Optional<PaymentIdempotency> existing =
                idempotencyRepo.find(command.userId(), command.idempotencyKey());
        if (existing.isPresent()) {
            switch (existing.get().resolve()) {
                case REPLAY -> {
                    return PaymentResultMapper.toResult(paymentRepo.findById(existing.get().paymentId())
                            .orElseThrow(() -> new BusinessException(ErrorCode.INCONSISTENT_STATE,
                                    "멱등성이 가리키는 결제 행이 없습니다: " + existing.get().paymentId())));
                }
                case IN_PROGRESS -> throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
                case PROCEED_NEW -> { /* FAILED는 별개 시도로 신규 진행 (설계 7-3) */ }
            }
        }

        Reservation reservation;
        try {
            reservation = tx.reserve(command);
        } catch (PessimisticLockingFailureException e) {
            // 같은 사용자의 다른 결제가 잔액·한도 락 보유 중(1초 초과 대기) → 서버 오류가 아닌 409 (설계 9-4).
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
        }
        tx.markPgCalling(command.userId(), command.idempotencyKey(), reservation.pgIdempotencyKey());

        List<Charged> charged = new ArrayList<>();
        for (PaymentAllocation allocation : reservation.externalAllocations()) {
            // 멱등키를 allocation마다 파생하는 이유: 분할 결제에서 base 키를 그대로 쓰면
            // PG가 두 번째 청구를 첫 번째의 중복 재시도로 오인해 무시한다 (결정 D-16).
            PgChargeResult chargeResult = pgPort.charge(new PgChargeRequest(
                    reservation.pgIdempotencyKey() + ":" + allocation.id(),
                    allocation.methodType(), allocation.methodId(), allocation.amount()));
            switch (chargeResult.outcome()) {
                case APPROVED -> charged.add(new Charged(allocation.id(), chargeResult.pgTransactionId()));
                case DECLINED -> {
                    tx.markPgStatus(command.userId(), command.idempotencyKey(), PgCallStatus.FAILED);
                    // 이미 승인된 앞선 청구(charged)는 회수 대상으로 보상에 넘긴다 (시나리오 23).
                    tx.compensate(reservation.paymentId(), command.userId(), command.idempotencyKey(),
                            PaymentStatus.FAILED, charged);
                    throw new BusinessException(ErrorCode.PG_DECLINED, chargeResult.declineReason());
                }
                case TIMEOUT -> {
                    // 청구 여부를 모르는 상태 — 원복하면 이중 상태가 될 수 있어 UNKNOWN으로 남기고,
                    // 보정 배치가 PG 진실을 조회해 확정한다 (설계 6-3).
                    tx.markPgStatus(command.userId(), command.idempotencyKey(), PgCallStatus.UNKNOWN);
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR);
                }
            }
        }

        tx.markPgStatus(command.userId(), command.idempotencyKey(), PgCallStatus.SUCCESS);
        PaymentResult result;
        try {
            result = tx.confirm(
                    reservation.paymentId(), command.userId(), command.idempotencyKey(), charged);
        } catch (RuntimeException e) {
            // TX2 실패(시나리오 22): PG 청구는 성공했으므로 내부 원복 + 청구분 전체를 회수 대상으로 기록.
            tx.compensate(reservation.paymentId(), command.userId(), command.idempotencyKey(),
                    PaymentStatus.FAILED_REFUNDED, charged);
            throw new BusinessException(ErrorCode.INCONSISTENT_STATE);
        }

        publisher.paymentCreated(new PaymentCreatedEvent(
                result.paymentId(), result.userId(), result.orderId(),
                result.totalAmount().value(), clock.instant()));
        return result;
    }
}
