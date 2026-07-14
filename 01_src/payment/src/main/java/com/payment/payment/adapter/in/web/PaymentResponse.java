package com.payment.payment.adapter.in.web;

import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;

/** POST /payments 성공 응답 본문 (설계 6-1). 금액은 원 단위 정수로 노출한다. */
record PaymentResponse(
        String paymentId,
        PaymentStatus status,
        String userId,
        String orderId,
        long totalAmount,
        Instant paidAt,
        List<AllocationResponse> allocations) {

    record AllocationResponse(
            PaymentMethodType methodType,
            String methodId,
            long amount,
            AllocationStatus status,
            String pgTransactionId) {}

    static PaymentResponse from(PaymentResult result) {
        return new PaymentResponse(result.paymentId(), result.status(), result.userId(),
                result.orderId(), result.totalAmount().value(), result.paidAt(),
                result.allocations().stream()
                        .map(alloc -> new AllocationResponse(alloc.methodType(), alloc.methodId(),
                                alloc.amount().value(), alloc.status(), alloc.pgTransactionId()))
                        .toList());
    }
}
