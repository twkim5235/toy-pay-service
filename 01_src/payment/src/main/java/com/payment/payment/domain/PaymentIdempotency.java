package com.payment.payment.domain;

import java.time.Instant;

/**
 * 결제 멱등성 (설계 7-3 payment_idempotency). 순수 도메인.
 *
 * <p>같은 {@code Idempotency-Key}로 중복 요청이 와도 한 번만 처리하도록 상태를 추적한다(설계 4-1 시나리오 15).
 * COMPLETED는 기존 결과 반환, PENDING은 진행 중(409), FAILED는 신규 재시도 허용 — 결제 실패 원인(한도·PG 거절)은
 * 시간이 지나면 해소될 수 있어 별개 시도로 다룬다. {@code pgCallStatus}는 PG 호출 불확실성 보정 배치의
 * 대상 판별에 쓰인다(설계 6-3). {@code expiredAt}은 멱등 보증 유효기간으로, 정책값을 애플리케이션이 계산해 주입한다.
 */
public class PaymentIdempotency {

    private final String idempotencyKey;
    private final String userId;
    private final String orderId;
    private final String paymentId;
    private IdempotencyStatus status;
    private PgCallStatus pgCallStatus;
    private String pgIdempotencyKey; // PG 호출 직전(markCalling) 보존, 그 전엔 null
    private final Instant expiredAt;

    PaymentIdempotency(String idempotencyKey, String userId, String orderId, String paymentId,
            IdempotencyStatus status, PgCallStatus pgCallStatus, String pgIdempotencyKey, Instant expiredAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.status = status;
        this.pgCallStatus = pgCallStatus;
        this.pgIdempotencyKey = pgIdempotencyKey;
        this.expiredAt = expiredAt;
    }

    public static PaymentIdempotency start(String idempotencyKey, String userId, String orderId,
            String paymentId, Instant expiredAt) {
        return new PaymentIdempotency(idempotencyKey, userId, orderId, paymentId,
                IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED, null, expiredAt);
    }

    /** DB 복원용. */
    public static PaymentIdempotency restore(String idempotencyKey, String userId, String orderId,
            String paymentId, IdempotencyStatus status, PgCallStatus pgCallStatus,
            String pgIdempotencyKey, Instant expiredAt) {
        return new PaymentIdempotency(idempotencyKey, userId, orderId, paymentId, status, pgCallStatus,
                pgIdempotencyKey, expiredAt);
    }

    public IdempotencyDecision resolve() {
        return switch (status) {
            case COMPLETED -> IdempotencyDecision.REPLAY;
            case PENDING -> IdempotencyDecision.IN_PROGRESS;
            case FAILED -> IdempotencyDecision.PROCEED_NEW;
        };
    }

    public void markCalling(String pgIdempotencyKey) {
        this.pgCallStatus = PgCallStatus.CALLING;
        this.pgIdempotencyKey = pgIdempotencyKey;
    }

    public void markPgStatus(PgCallStatus pgCallStatus) {
        this.pgCallStatus = pgCallStatus;
    }

    public void complete() {
        this.status = IdempotencyStatus.COMPLETED;
        this.pgCallStatus = PgCallStatus.SUCCESS;
    }

    public void fail() {
        this.status = IdempotencyStatus.FAILED;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public String userId() {
        return userId;
    }

    public String orderId() {
        return orderId;
    }

    public String paymentId() {
        return paymentId;
    }

    public IdempotencyStatus status() {
        return status;
    }

    public PgCallStatus pgCallStatus() {
        return pgCallStatus;
    }

    public String pgIdempotencyKey() {
        return pgIdempotencyKey;
    }

    public Instant expiredAt() {
        return expiredAt;
    }
}
