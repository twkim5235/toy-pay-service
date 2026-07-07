package com.payment.balance.domain;

/** 잔액 변경 종류 (설계 7-2 balance_history.action). 본 스코프(결제 본류)에서 다루는 값만 정의 (REFUND는 [2차]). */
public enum BalanceAction {
    CHARGE,
    PAYMENT,
    ROLLBACK
}
