package com.payment.payment.application.port.out;

/** 결제 이벤트 발행 (driven 포트, 설계 10 payment-created). */
public interface PaymentEventPublisher {

    void paymentCreated(PaymentCreatedEvent event);
}
