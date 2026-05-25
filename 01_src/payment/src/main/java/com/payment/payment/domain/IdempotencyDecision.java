package com.payment.payment.domain;

/** 멱등성 키 조회 결과에 따른 처리 분기 (설계 4-1 시나리오 15). */
public enum IdempotencyDecision {
    /** 기존 키 없음 또는 FAILED — 신규 처리 진행. */
    PROCEED_NEW,
    /** COMPLETED — 기존 결제 결과를 그대로 반환(200). */
    REPLAY,
    /** PENDING — 동일 요청 처리 중(409). */
    IN_PROGRESS
}
