package com.payment.balance.adapter.out.persistence.usage;

import com.payment.balance.domain.UserDailyUsage;
import com.payment.common.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** user_daily_usage row 매핑 (설계 7-2). */
@Entity
@Table(name = "user_daily_usage")
class UserDailyUsageJpaEntity {

    @Id
    @Column(name = "user_id")
    private String userId;

    @Column(name = "used_amount", nullable = false)
    private long usedAmount;

    @Column(name = "last_reset_date", nullable = false)
    private LocalDate lastResetDate;

    protected UserDailyUsageJpaEntity() {
    }

    private UserDailyUsageJpaEntity(String userId, long usedAmount, LocalDate lastResetDate) {
        this.userId = userId;
        this.usedAmount = usedAmount;
        this.lastResetDate = lastResetDate;
    }

    static UserDailyUsageJpaEntity from(UserDailyUsage domain) {
        return new UserDailyUsageJpaEntity(
                domain.userId(), domain.usedAmount().value(), domain.lastResetDate());
    }

    UserDailyUsage toDomain() {
        return new UserDailyUsage(userId, Money.won(usedAmount), lastResetDate);
    }
}
