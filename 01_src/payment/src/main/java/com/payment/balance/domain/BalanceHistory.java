package com.payment.balance.domain;

import com.payment.common.Money;

/**
 * 잔액 변경 감사 로그 (설계 7-2 balance_history). 순수 도메인, INSERT-only.
 *
 * <p>돈을 다루는 시스템의 법적 보존 의무를 위해 모든 잔액 변경을 부호 포함 변화량으로 남긴다.
 * 충전·결제·원복은 잔액에 영향을 주므로 기록되고, 거래 종류는 {@link BalanceAction}으로,
 * 출처는 {@code paymentId}/{@code chargeId}로 추적한다. {@code balanceAfter}는 "특정 시점 잔액"
 * 조회 핫패스를 위해 절대값을 직접 저장한다.
 */
public class BalanceHistory {

    private final String userId;
    private final BalanceAction action;
    private final long amountChange; // +충전/원복, -결제
    private final long balanceAfter;
    private final String paymentId;
    private final String chargeId;

    private BalanceHistory(String userId, BalanceAction action, long amountChange,
            long balanceAfter, String paymentId, String chargeId) {
        this.userId = userId;
        this.action = action;
        this.amountChange = amountChange;
        this.balanceAfter = balanceAfter;
        this.paymentId = paymentId;
        this.chargeId = chargeId;
    }

    public static BalanceHistory payment(String userId, Money amount, Money balanceAfter, String paymentId) {
        return new BalanceHistory(userId, BalanceAction.PAYMENT, -amount.value(), balanceAfter.value(), paymentId, null);
    }

    public static BalanceHistory rollback(String userId, Money amount, Money balanceAfter, String paymentId) {
        return new BalanceHistory(userId, BalanceAction.ROLLBACK, amount.value(), balanceAfter.value(), paymentId, null);
    }

    public static BalanceHistory charge(String userId, Money amount, Money balanceAfter, String chargeId) {
        return new BalanceHistory(userId, BalanceAction.CHARGE, amount.value(), balanceAfter.value(), null, chargeId);
    }

    public String userId() {
        return userId;
    }

    public BalanceAction action() {
        return action;
    }

    public long amountChange() {
        return amountChange;
    }

    public long balanceAfter() {
        return balanceAfter;
    }

    public String paymentId() {
        return paymentId;
    }

    public String chargeId() {
        return chargeId;
    }
}
