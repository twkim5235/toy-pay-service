package com.payment.payment.application.port.out;

import java.time.Instant;

/**
 * 결제 완료 이벤트 (설계 10 payment-created). 본 스코프는 최소 payload만 발행하고,
 * 정식 계약 DTO는 로드맵 item 3(:event-contracts 모듈)에서 정리한다.
 */
public record PaymentCreatedEvent(
        String paymentId,
        String userId,
        String orderId,
        long totalAmount,
        Instant occurredAt) {}
