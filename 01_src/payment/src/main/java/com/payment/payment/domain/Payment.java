package com.payment.payment.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 결제 애그리거트 (설계 7-1 payments). 순수 도메인.
 *
 * <p>한 결제를 여러 결제수단으로 나눠 내는 분할 결제가 메인 시나리오다(설계 4-1, 13). 생성 시
 * 분배 규칙을 불변식으로 강제한다(설계 8-1 서버 측 검증):
 * <ul>
 *   <li>분배 금액의 합 == {@code total_amount}</li>
 *   <li>BALANCE allocation은 최대 1개 (사용자 잔액은 한 곳)</li>
 *   <li>외부 수단의 {@code method_id}는 중복되지 않음</li>
 * </ul>
 * PG 호출·상태 전이(PAID/FAILED)는 애플리케이션이 오케스트레이션하며, 본 애그리거트는 규칙과 전이만 표현한다.
 */
public class Payment {

    private final String id;
    private final String userId;
    private final String orderId;
    private final Money totalAmount;
    private final List<PaymentAllocation> allocations;
    private PaymentStatus status;
    private Instant paidAt;

    Payment(String id, String userId, String orderId, Money totalAmount,
            List<PaymentAllocation> allocations, PaymentStatus status, Instant paidAt) {
        if (allocations == null || allocations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "allocations가 비어 있습니다");
        }
        Money sum = allocations.stream().map(PaymentAllocation::amount).reduce(Money.ZERO, Money::plus);
        if (!sum.equals(totalAmount)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분배 합이 total_amount와 다릅니다");
        }
        if (allocations.stream().filter(a -> a.methodType() == PaymentMethodType.BALANCE).count() > 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "BALANCE 수단은 최대 1개입니다");
        }
        long externalCount = allocations.stream().filter(PaymentAllocation::isExternal).count();
        long distinctMethodIds = allocations.stream().filter(PaymentAllocation::isExternal)
                .map(PaymentAllocation::methodId).distinct().count();
        if (externalCount != distinctMethodIds) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "method_id가 중복됩니다");
        }
        this.id = id;
        this.userId = userId;
        this.orderId = orderId;
        this.totalAmount = totalAmount;
        this.allocations = List.copyOf(allocations);
        this.status = status;
        this.paidAt = paidAt;
    }

    public static Payment create(String userId, String orderId, Money totalAmount,
            List<PaymentAllocation> allocations) {
        return new Payment(UUID.randomUUID().toString(), userId, orderId, totalAmount,
                allocations, PaymentStatus.PENDING, null);
    }

    /** DB 복원용. */
    public static Payment restore(String id, String userId, String orderId, Money totalAmount,
            List<PaymentAllocation> allocations, PaymentStatus status, Instant paidAt) {
        return new Payment(id, userId, orderId, totalAmount, allocations, status, paidAt);
    }

    public Money balanceAmount() {
        return allocations.stream()
                .filter(a -> a.methodType() == PaymentMethodType.BALANCE)
                .map(PaymentAllocation::amount)
                .findFirst()
                .orElse(Money.ZERO);
    }

    public List<PaymentAllocation> externalAllocations() {
        return allocations.stream().filter(PaymentAllocation::isExternal).toList();
    }

    public void markPaid(Instant at) {
        this.status = PaymentStatus.PAID;
        this.paidAt = at;
    }

    public void markFailed() {
        this.status = PaymentStatus.FAILED;
    }

    public void markFailedRefunded() {
        this.status = PaymentStatus.FAILED_REFUNDED;
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public String orderId() {
        return orderId;
    }

    public Money totalAmount() {
        return totalAmount;
    }

    public List<PaymentAllocation> allocations() {
        return allocations;
    }

    public PaymentStatus status() {
        return status;
    }

    public Instant paidAt() {
        return paidAt;
    }
}
