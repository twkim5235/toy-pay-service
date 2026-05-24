package com.payment.balance.domain;

import com.payment.common.Money;

/**
 * 사용자 일일 결제 한도 애그리거트 (설계 7-2 user_daily_limit). 순수 도메인.
 *
 * <p>한도값은 KYC 등급 등 정책으로 정해지며(설계 7-2, 본 스코프 밖) 본 도메인은 읽기 전용으로 본다.
 * "한도 초과"의 의미(누적 예상치가 한도를 초과)를 본 애그리거트가 소유해 단일 출처로 둔다.
 */
public class UserDailyLimit {

    private final String userId;
    private final Money dailyLimit;

    public UserDailyLimit(String userId, Money dailyLimit) {
        this.userId = userId;
        this.dailyLimit = dailyLimit;
    }

    /** 결제 후 누적 예상치가 한도를 넘으면 true. 한도와 같으면 허용(false). */
    public boolean wouldExceed(Money projectedUsedAmount) {
        return dailyLimit.isLessThan(projectedUsedAmount);
    }

    public String userId() {
        return userId;
    }

    public Money value() {
        return dailyLimit;
    }
}
