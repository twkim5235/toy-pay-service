package com.payment.payment.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.util.UUID;

/**
 * 결제수단별 분배 (설계 7-1 payment_allocation). 순수 도메인.
 *
 * <p>한 결제는 N개의 allocation으로 분할된다(분할 결제가 메인 시나리오). 외부 수단(CARD/ACCOUNT)은
 * {@code method_id}가 필수이며 PG 청구 대상이다. BALANCE는 {@code method_id}가 없고 내부 잔액에서 차감한다.
 * {@code pg_transaction_id}는 PG 응답의 거래 ID로, 청구 성공(settle) 시 보존해 환불 처리(2차)의 근거가 된다.
 */
public class PaymentAllocation {

    private final String id;
    private final PaymentMethodType methodType;
    private final String methodId; // BALANCE면 null
    private final Money amount;
    private AllocationStatus status;
    private String pgTransactionId;

    PaymentAllocation(String id, PaymentMethodType methodType, String methodId,
            Money amount, AllocationStatus status, String pgTransactionId) {
        if (methodType.isExternal() && (methodId == null || methodId.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "외부 결제수단은 method_id가 필요합니다");
        }
        if (methodType == PaymentMethodType.BALANCE && methodId != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "BALANCE 수단은 method_id를 가질 수 없습니다");
        }
        if (!amount.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분배 금액은 양수여야 합니다");
        }
        this.id = id;
        this.methodType = methodType;
        this.methodId = methodId;
        this.amount = amount;
        this.status = status;
        this.pgTransactionId = pgTransactionId;
    }

    public static PaymentAllocation balance(Money amount) {
        return new PaymentAllocation(UUID.randomUUID().toString(),
                PaymentMethodType.BALANCE, null, amount, AllocationStatus.PENDING, null);
    }

    public static PaymentAllocation external(PaymentMethodType methodType, String methodId, Money amount) {
        return new PaymentAllocation(UUID.randomUUID().toString(),
                methodType, methodId, amount, AllocationStatus.PENDING, null);
    }

    /** DB 복원용. */
    public static PaymentAllocation restore(String id, PaymentMethodType methodType, String methodId,
            Money amount, AllocationStatus status, String pgTransactionId) {
        return new PaymentAllocation(id, methodType, methodId, amount, status, pgTransactionId);
    }

    public void settle(String pgTransactionId) {
        this.status = AllocationStatus.SETTLED;
        this.pgTransactionId = pgTransactionId;
    }

    public void fail() {
        this.status = AllocationStatus.FAILED;
    }

    public boolean isExternal() {
        return methodType.isExternal();
    }

    public String id() {
        return id;
    }

    public PaymentMethodType methodType() {
        return methodType;
    }

    public String methodId() {
        return methodId;
    }

    public Money amount() {
        return amount;
    }

    public AllocationStatus status() {
        return status;
    }

    public String pgTransactionId() {
        return pgTransactionId;
    }
}
