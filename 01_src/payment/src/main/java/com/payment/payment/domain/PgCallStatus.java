package com.payment.payment.domain;

/**
 * PG 호출 추적 상태 (설계 7-3 payment_idempotency.pg_call_status, 6-3 보정 배치 대상 판별).
 * UNKNOWN(타임아웃)은 보정 배치가 PG 진실을 조회해 SUCCESS/FAILED로 확정한다.
 */
public enum PgCallStatus {
    NOT_CALLED,
    CALLING,
    SUCCESS,
    FAILED,
    UNKNOWN
}
