package com.payment.balance.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.time.LocalDate;

/**
 * 사용자 일일 결제 누적 애그리거트 (설계 7-2 user_daily_usage). 순수 도메인.
 *
 * <p>자정 리셋은 <b>lazy</b>로 처리한다: 결제·원복 시점에 마지막 리셋일이 today 이전이면
 * 누적을 0으로 되돌린 뒤 진행한다. 자정 배치(설계 7-2)는 정합성 보조 수단일 뿐 정확성은
 * 이 lazy 리셋이 보장한다. {@code today}는 외부에서 주입받아 도메인이 시스템 시계에 의존하지 않게 한다.
 *
 * <p>충전은 본 누적에 영향이 없다(설계 0장 비대칭).
 */
public class UserDailyUsage {

    private final String userId;
    private Money usedAmount;
    private LocalDate lastResetDate;

    public UserDailyUsage(String userId, Money usedAmount, LocalDate lastResetDate) {
        this.userId = userId;
        this.usedAmount = usedAmount;
        this.lastResetDate = lastResetDate;
    }

    /** 신규 사용자 — 누적 0, 리셋일 today. */
    public static UserDailyUsage create(String userId, LocalDate today) {
        return new UserDailyUsage(userId, Money.ZERO, today);
    }

    /** 결제 누적(+). 한도 초과 시 {@link ErrorCode#DAILY_LIMIT_EXCEEDED}, 누적 불변. */
    public void use(Money amount, UserDailyLimit dailyLimit, LocalDate today) {
        resetIfNewDay(today);
        Money projected = usedAmount.plus(amount);
        if (dailyLimit.wouldExceed(projected)) {
            throw new BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED);
        }
        this.usedAmount = projected;
    }

    /** 결제 누적 원복(-). 누적이 부족하면 0으로 클램프(자정 리셋으로 어제분이 사라진 경우 보호). */
    public void rollback(Money amount, LocalDate today) {
        resetIfNewDay(today);
        this.usedAmount = usedAmount.isLessThan(amount) ? Money.ZERO : usedAmount.minus(amount);
    }

    private void resetIfNewDay(LocalDate today) {
        if (lastResetDate.isBefore(today)) {
            this.usedAmount = Money.ZERO;
            this.lastResetDate = today;
        }
    }

    public String userId() {
        return userId;
    }

    public Money usedAmount() {
        return usedAmount;
    }

    public LocalDate lastResetDate() {
        return lastResetDate;
    }
}
