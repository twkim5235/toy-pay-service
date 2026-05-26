package com.payment.payment.application;

import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.domain.Payment;

/** {@link Payment} 애그리거트를 응답 DTO로 옮긴다. application 내부 전용. */
final class PaymentResultMapper {

    private PaymentResultMapper() {
    }

    static PaymentResult toResult(Payment payment) {
        return new PaymentResult(
                payment.id(), payment.status(), payment.userId(), payment.orderId(),
                payment.totalAmount(), payment.paidAt(),
                payment.allocations().stream()
                        .map(a -> new PaymentResult.Alloc(
                                a.methodType(), a.methodId(), a.amount(), a.status(), a.pgTransactionId()))
                        .toList());
    }
}
