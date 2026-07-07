package com.payment.payment.adapter.out.persistence.compensation;

import com.payment.common.Money;
import com.payment.payment.application.port.out.CompensationFailureRecorder.FailureType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * compensating_transaction_failures row 매핑 (설계 6-4). INSERT-only 수동 복구 장부라 읽기·수정 매핑이 없다.
 * id는 DB AUTO_INCREMENT, failed_at은 DB 기본값이 관리하므로 매핑하지 않는다. charge_id는 payment-core
 * 결제 흐름에선 항상 NULL이라 매핑하지 않는다(충전 컨텍스트에서 쓰인다).
 */
@Entity
@Table(name = "compensating_transaction_failures")
class CompensationFailureJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", length = 64)
    private String paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_type", nullable = false, length = 32)
    private FailureType failureType;

    @Column(name = "amount")
    private Long amount;

    protected CompensationFailureJpaEntity() {
    }

    private CompensationFailureJpaEntity(String paymentId, FailureType failureType, Long amount) {
        this.paymentId = paymentId;
        this.failureType = failureType;
        this.amount = amount;
    }

    static CompensationFailureJpaEntity from(String paymentId, FailureType failureType, Money amount) {
        return new CompensationFailureJpaEntity(paymentId, failureType, amount.value());
    }
}
