package com.payment.balance.adapter.out.persistence.history;

import com.payment.balance.domain.BalanceAction;
import com.payment.balance.domain.BalanceHistory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * balance_history row 매핑 (설계 7-2). INSERT-only 감사 로그라 읽기·수정 매핑이 없다.
 * id는 DB AUTO_INCREMENT, created_at은 DB 기본값이 관리하므로 매핑하지 않는다.
 * 스키마는 Flyway 단독 소유이며 본 엔티티-스키마 정합성은 Testcontainers 통합테스트로 검증한다.
 */
@Entity
@Table(name = "balance_history")
class BalanceHistoryJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 16)
    private BalanceAction action;

    @Column(name = "amount_change", nullable = false)
    private long amountChange;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(name = "charge_id")
    private String chargeId;

    protected BalanceHistoryJpaEntity() {
    }

    private BalanceHistoryJpaEntity(String userId, BalanceAction action, long amountChange,
            long balanceAfter, String paymentId, String chargeId) {
        this.userId = userId;
        this.action = action;
        this.amountChange = amountChange;
        this.balanceAfter = balanceAfter;
        this.paymentId = paymentId;
        this.chargeId = chargeId;
    }

    static BalanceHistoryJpaEntity from(BalanceHistory domain) {
        return new BalanceHistoryJpaEntity(domain.userId(), domain.action(), domain.amountChange(),
                domain.balanceAfter(), domain.paymentId(), domain.chargeId());
    }
}
