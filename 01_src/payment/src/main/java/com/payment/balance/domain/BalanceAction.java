package com.payment.balance.domain;

/** 잔액 변경 종류 (설계 7-2 balance_history.action). */
public enum BalanceAction {
    CHARGE,
    PAYMENT,
    REFUND,
    ROLLBACK
}
