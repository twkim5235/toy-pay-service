package com.payment.payment.adapter.out.persistence.payment;

import com.payment.common.Money;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * payments row 매핑 (설계 7-1). 애그리거트 루트로 allocation을 cascade 소유한다(save/조회를 한 단위로).
 * created_at/updated_at은 DB 기본값·ON UPDATE가 관리하므로 매핑하지 않는다.
 * 스키마는 Flyway 단독 소유이며 정합성은 Testcontainers 통합테스트로 검증한다.
 */
@Entity
@Table(name = "payments")
class PaymentJpaEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "paid_at")
    private Instant paidAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.EAGER)
    private List<PaymentAllocationJpaEntity> allocations = new ArrayList<>();

    protected PaymentJpaEntity() {
    }

    private PaymentJpaEntity(String id, String userId, String orderId, long totalAmount,
            PaymentStatus status, Instant paidAt) {
        this.id = id;
        this.userId = userId;
        this.orderId = orderId;
        this.totalAmount = totalAmount;
        this.status = status;
        this.paidAt = paidAt;
    }

    static PaymentJpaEntity from(Payment domain) {
        PaymentJpaEntity entity = new PaymentJpaEntity(domain.id(), domain.userId(), domain.orderId(),
                domain.totalAmount().value(), domain.status(), domain.paidAt());
        for (PaymentAllocation allocation : domain.allocations()) {
            entity.allocations.add(PaymentAllocationJpaEntity.from(allocation, entity));
        }
        return entity;
    }

    Payment toDomain() {
        List<PaymentAllocation> domainAllocations =
                allocations.stream().map(PaymentAllocationJpaEntity::toDomain).toList();
        return Payment.restore(id, userId, orderId, Money.won(totalAmount), domainAllocations,
                status, paidAt);
    }
}
