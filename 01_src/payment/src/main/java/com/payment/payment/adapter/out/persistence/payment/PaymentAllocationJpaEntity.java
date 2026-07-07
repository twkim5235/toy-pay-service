package com.payment.payment.adapter.out.persistence.payment;

import com.payment.common.Money;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentMethodType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * payment_allocation row 매핑 (설계 7-1). 애그리거트 자식으로, NOT NULL FK인 payment_id를 소유하는
 * owning side다(양방향). created_at/updated_at은 DB 기본값이 관리하므로 매핑하지 않는다.
 */
@Entity
@Table(name = "payment_allocation")
class PaymentAllocationJpaEntity {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private PaymentJpaEntity payment;

    @Enumerated(EnumType.STRING)
    @Column(name = "method_type", nullable = false, length = 32)
    private PaymentMethodType methodType;

    @Column(name = "method_id", length = 64)
    private String methodId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AllocationStatus status;

    @Column(name = "pg_transaction_id", length = 128)
    private String pgTransactionId;

    protected PaymentAllocationJpaEntity() {
    }

    private PaymentAllocationJpaEntity(String id, PaymentJpaEntity payment, PaymentMethodType methodType,
            String methodId, long amount, AllocationStatus status, String pgTransactionId) {
        this.id = id;
        this.payment = payment;
        this.methodType = methodType;
        this.methodId = methodId;
        this.amount = amount;
        this.status = status;
        this.pgTransactionId = pgTransactionId;
    }

    static PaymentAllocationJpaEntity from(PaymentAllocation domain, PaymentJpaEntity payment) {
        return new PaymentAllocationJpaEntity(domain.id(), payment, domain.methodType(), domain.methodId(),
                domain.amount().value(), domain.status(), domain.pgTransactionId());
    }

    PaymentAllocation toDomain() {
        return PaymentAllocation.restore(id, methodType, methodId, Money.won(amount), status, pgTransactionId);
    }
}
