package com.payment.payment.domain;

/** 멱등성 처리 상태 (설계 7-3 payment_idempotency.status). */
public enum IdempotencyStatus {
    PENDING,
    COMPLETED,
    FAILED
}
