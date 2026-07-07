package com.payment.payment.domain;

/** 결제 상태 (설계 7-1 payments.status). 본 스코프(결제 본류)에서 다루는 값만 정의. */
public enum PaymentStatus {
    PENDING,
    PAID,
    FAILED,
    FAILED_REFUNDED
}
