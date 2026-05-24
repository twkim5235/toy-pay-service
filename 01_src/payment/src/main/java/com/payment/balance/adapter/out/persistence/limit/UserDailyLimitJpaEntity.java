package com.payment.balance.adapter.out.persistence.limit;

import com.payment.balance.domain.UserDailyLimit;
import com.payment.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** user_daily_limit row 매핑 (설계 7-2). */
@Entity
@Table(name = "user_daily_limit")
class UserDailyLimitJpaEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "daily_limit", nullable = false)
    private long dailyLimit;

    protected UserDailyLimitJpaEntity() {
    }

    private UserDailyLimitJpaEntity(String userId, long dailyLimit) {
        this.userId = userId;
        this.dailyLimit = dailyLimit;
    }

    static UserDailyLimitJpaEntity from(UserDailyLimit domain) {
        return new UserDailyLimitJpaEntity(domain.userId(), domain.value().value());
    }

    UserDailyLimit toDomain() {
        return new UserDailyLimit(userId, Money.won(dailyLimit));
    }
}
