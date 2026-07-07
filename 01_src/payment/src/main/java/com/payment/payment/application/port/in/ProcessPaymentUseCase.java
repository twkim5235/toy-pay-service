package com.payment.payment.application.port.in;

/** 결제 처리 유스케이스 (driving 포트, 설계 8-1 POST /payments). */
public interface ProcessPaymentUseCase {

    PaymentResult process(ProcessPaymentCommand command);
}
