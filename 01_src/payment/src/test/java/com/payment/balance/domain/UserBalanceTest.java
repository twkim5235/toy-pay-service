package com.payment.balance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserBalance 잔액 도메인")
class UserBalanceTest {

    @Test
    @DisplayName("신규 사용자의 잔액은 0이다")
    void 신규_사용자_잔액은_0() {
        UserBalance balance = UserBalance.create("user-1");

        assertThat(balance.balance()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("충전하면 잔액이 증가한다")
    void 충전하면_잔액_증가() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));

        balance.charge(Money.won(5_000));

        assertThat(balance.balance()).isEqualTo(Money.won(15_000));
    }

    @Test
    @DisplayName("차감하면 잔액이 감소한다")
    void 차감하면_잔액_감소() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));

        balance.deduct(Money.won(3_000));

        assertThat(balance.balance()).isEqualTo(Money.won(7_000));
    }

    @Test
    @DisplayName("잔액 전액을 차감해 0이 될 수 있다 (경계)")
    void 전액_차감_가능() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));

        balance.deduct(Money.won(10_000));

        assertThat(balance.balance()).isEqualTo(Money.ZERO);
    }

    @Test
    @DisplayName("잔액보다 큰 금액을 차감하면 INSUFFICIENT_BALANCE 예외 (잔액 음수 방지)")
    void 잔액부족이면_예외() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));

        assertThatThrownBy(() -> balance.deduct(Money.won(10_001)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
    }

    @Test
    @DisplayName("차감 실패 시 잔액은 변하지 않는다")
    void 차감_실패_시_잔액_불변() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));

        assertThatThrownBy(() -> balance.deduct(Money.won(20_000)))
                .isInstanceOf(BusinessException.class);

        assertThat(balance.balance()).isEqualTo(Money.won(10_000));
    }

    @Test
    @DisplayName("rollback하면 차감했던 금액이 복구된다")
    void rollback하면_차감분_복구() {
        UserBalance balance = new UserBalance("user-1", Money.won(10_000));
        balance.deduct(Money.won(4_000));

        balance.rollback(Money.won(4_000));

        assertThat(balance.balance()).isEqualTo(Money.won(10_000));
    }
}
