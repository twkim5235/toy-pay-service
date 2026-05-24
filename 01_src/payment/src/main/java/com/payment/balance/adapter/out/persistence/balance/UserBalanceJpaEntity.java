package com.payment.balance.adapter.out.persistence.balance;

import com.payment.balance.domain.UserBalance;
import com.payment.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * user_balance row 매핑 (설계 7-2). created_at/updated_at은 DB 기본값/ON UPDATE가 관리하므로 매핑하지 않는다.
 * 스키마는 Flyway 단독 소유이며 본 엔티티-스키마 정합성은 Testcontainers 통합테스트로 검증한다.
 */
@Entity
@Table(name = "user_balance")
class UserBalanceJpaEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "balance", nullable = false)
    private long balance;

    protected UserBalanceJpaEntity() {
    }

    private UserBalanceJpaEntity(String userId, long balance) {
        this.userId = userId;
        this.balance = balance;
    }

    static UserBalanceJpaEntity from(UserBalance domain) {
        return new UserBalanceJpaEntity(domain.userId(), domain.balance().value());
    }

    UserBalance toDomain() {
        return new UserBalance(userId, Money.won(balance));
    }
}
