package com.payment.balance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BalanceHistoryTest {

    @Test
    @DisplayName("결제 차감은 음수 변화량(PAYMENT)으로 기록되고 payment_id를 보존한다")
    void 결제_차감은_음수변화량() {
        BalanceHistory h = BalanceHistory.payment("u1", Money.won(30_000), Money.won(70_000), "PAY1");

        assertThat(h.userId()).isEqualTo("u1");
        assertThat(h.action()).isEqualTo(BalanceAction.PAYMENT);
        assertThat(h.amountChange()).isEqualTo(-30_000);
        assertThat(h.balanceAfter()).isEqualTo(70_000);
        assertThat(h.paymentId()).isEqualTo("PAY1");
        assertThat(h.chargeId()).isNull();
    }

    @Test
    @DisplayName("보상 원복은 양수 변화량(ROLLBACK)으로 기록된다")
    void 원복은_양수변화량() {
        BalanceHistory h = BalanceHistory.rollback("u1", Money.won(30_000), Money.won(100_000), "PAY1");

        assertThat(h.action()).isEqualTo(BalanceAction.ROLLBACK);
        assertThat(h.amountChange()).isEqualTo(30_000);
        assertThat(h.balanceAfter()).isEqualTo(100_000);
        assertThat(h.paymentId()).isEqualTo("PAY1");
    }

    @Test
    @DisplayName("충전은 양수 변화량(CHARGE)으로 기록되고 charge_id를 보존한다")
    void 충전은_양수변화량() {
        BalanceHistory h = BalanceHistory.charge("u1", Money.won(50_000), Money.won(150_000), "CHG1");

        assertThat(h.action()).isEqualTo(BalanceAction.CHARGE);
        assertThat(h.amountChange()).isEqualTo(50_000);
        assertThat(h.balanceAfter()).isEqualTo(150_000);
        assertThat(h.chargeId()).isEqualTo("CHG1");
        assertThat(h.paymentId()).isNull();
    }
}
