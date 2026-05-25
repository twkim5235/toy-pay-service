package com.payment.payment.domain;

/** 결제수단 종류 (설계 7-1 payment_allocation.method_type). BALANCE는 내부 잔액, 그 외는 PG 청구 대상. */
public enum PaymentMethodType {
    BALANCE,
    CARD,
    ACCOUNT;

    /** 외부 결제수단(PG 청구 대상)인지 — BALANCE만 내부 처리. */
    public boolean isExternal() {
        return this != BALANCE;
    }
}
