package com.payment.payment.domain;

/** 결제수단별 분배 상태 (설계 7-1 payment_allocation.status). */
public enum AllocationStatus {
    PENDING,
    SETTLED,
    FAILED,
    REFUNDED
}
