package com.payment.payment.adapter.out.persistence.idempotency;

import com.payment.payment.domain.IdempotencyStatus;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PgCallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * payment_idempotency row 매핑 (설계 7-3). retry_count/created_at/updated_at은 DB 기본값·ON UPDATE가
 * 관리하므로 매핑하지 않는다. 스키마는 Flyway 단독 소유이며 정합성은 Testcontainers 통합테스트로 검증한다.
 */
@Entity
@Table(name = "payment_idempotency")
class PaymentIdempotencyJpaEntity {

    @Id
    @Column(name = "idempotency_key", length = 64)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "pg_call_status", nullable = false, length = 32)
    private PgCallStatus pgCallStatus;

    @Column(name = "pg_idempotency_key", length = 128)
    private String pgIdempotencyKey;

    @Column(name = "expired_at", nullable = false)
    private Instant expiredAt;

    protected PaymentIdempotencyJpaEntity() {
    }

    private PaymentIdempotencyJpaEntity(String idempotencyKey, String userId, String orderId,
            String paymentId, IdempotencyStatus status, PgCallStatus pgCallStatus,
            String pgIdempotencyKey, Instant expiredAt) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.orderId = orderId;
        this.paymentId = paymentId;
        this.status = status;
        this.pgCallStatus = pgCallStatus;
        this.pgIdempotencyKey = pgIdempotencyKey;
        this.expiredAt = expiredAt;
    }

    static PaymentIdempotencyJpaEntity from(PaymentIdempotency domain) {
        return new PaymentIdempotencyJpaEntity(domain.idempotencyKey(), domain.userId(),
                domain.orderId(), domain.paymentId(), domain.status(), domain.pgCallStatus(),
                domain.pgIdempotencyKey(), domain.expiredAt());
    }

    PaymentIdempotency toDomain() {
        return PaymentIdempotency.restore(idempotencyKey, userId, orderId, paymentId,
                status, pgCallStatus, pgIdempotencyKey, expiredAt);
    }
}