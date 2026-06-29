package com.payment.payment.domain;

/** 결제수단별 분배 상태 (설계 7-1 payment_allocation.status). 본 스코프(결제 본류)에서 다루는 값만 정의 (REFUNDED는 [2차]). */
public enum AllocationStatus {
    PENDING,
    SETTLED,
    FAILED
}
