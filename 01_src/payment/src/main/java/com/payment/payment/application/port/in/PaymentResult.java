package com.payment.payment.application.port.in;

import com.payment.common.Money;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;

/** 결제 처리 결과 (설계 8-1 응답 본문). */
public record PaymentResult(
        String paymentId,
        PaymentStatus status,
        String userId,
        String orderId,
        Money totalAmount,
        Instant paidAt,
        List<Alloc> allocations) {

    public record Alloc(
            PaymentMethodType methodType,
            String methodId,
            Money amount,
            AllocationStatus status,
            String pgTransactionId) {}
}
