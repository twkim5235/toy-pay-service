package com.payment.balance.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;

/**
 * 사용자 잔액 애그리거트 (설계 7-2 user_balance). 순수 도메인 — 인프라(JPA/DB) 의존 없음.
 *
 * <p>잔액 음수 방지(설계 9-5)를 도메인에서 보장한다: 차감 시 잔액이 부족하면
 * {@link ErrorCode#INSUFFICIENT_BALANCE}로 거부하고 상태를 바꾸지 않는다.
 * 동시성(비관적 락)은 영속 어댑터의 책임이며 본 도메인은 단일 스레드 규칙만 표현한다(설계 9장).
 */
public class UserBalance {

    private final String userId;
    private Money balance;

    /** DB 복원/명시적 생성용. */
    public UserBalance(String userId, Money balance) {
        this.userId = userId;
        this.balance = balance;
    }

    /** 신규 사용자 — 잔액 0. */
    public static UserBalance create(String userId) {
        return new UserBalance(userId, Money.ZERO);
    }

    /** 충전(+). PG 성공 후 트랜잭션2에서 호출(설계 6-2). */
    public void charge(Money amount) {
        this.balance = this.balance.plus(amount);
    }

    /** 결제 차감(-). 잔액 부족 시 예외, 상태 불변. */
    public void deduct(Money amount) {
        if (balance.isLessThan(amount)) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        this.balance = this.balance.minus(amount);
    }

    /** 차감 보상(원복). PG 실패 등으로 차감을 되돌릴 때 호출(설계 시나리오 22). */
    public void rollback(Money amount) {
        this.balance = this.balance.plus(amount);
    }

    public String userId() {
        return userId;
    }

    public Money balance() {
        return balance;
    }
}
