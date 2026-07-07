package com.payment.payment.application.port.in;

import com.payment.common.Money;
import com.payment.payment.domain.PaymentMethodType;
import java.util.List;

/**
 * 결제 처리 입력 (설계 8-1 요청 본문 + Idempotency-Key 헤더).
 *
 * <p>{@code idempotencyKey}는 클라이언트가 생성해 헤더로 전달하는 값이다(설계 7-3, 서버 미생성).
 */
public record ProcessPaymentCommand(
        String idempotencyKey,
        String userId,
        String orderId,
        Money totalAmount,
        List<Line> allocations) {

    public record Line(PaymentMethodType methodType, String methodId, Money amount) {}
}
