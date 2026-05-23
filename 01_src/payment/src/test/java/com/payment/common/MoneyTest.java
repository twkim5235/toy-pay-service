package com.payment.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Money 값 객체")
class MoneyTest {

    @Test
    @DisplayName("음수 금액은 생성할 수 없다 (원 단위, 음수 불가 불변식)")
    void 음수_금액은_생성할_수_없다() {
        assertThatThrownBy(() -> Money.won(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("두 금액을 더한다")
    void 금액을_더한다() {
        assertThat(Money.won(300_000).plus(Money.won(700_000)))
                .isEqualTo(Money.won(1_000_000));
    }

    @Test
    @DisplayName("두 금액을 뺀다")
    void 금액을_뺀다() {
        assertThat(Money.won(1_000_000).minus(Money.won(300_000)))
                .isEqualTo(Money.won(700_000));
    }

    @Test
    @DisplayName("보유보다 큰 금액을 빼면 음수가 되어 예외 (잔액 음수 방지 불변식)")
    void 보유보다_큰_금액을_빼면_예외() {
        assertThatThrownBy(() -> Money.won(100_000).minus(Money.won(300_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("크거나 같은지 비교한다")
    void 크거나_같은지_비교() {
        assertThat(Money.won(1_000_000).isGreaterThanOrEqualTo(Money.won(1_000_000))).isTrue();
        assertThat(Money.won(999_999).isGreaterThanOrEqualTo(Money.won(1_000_000))).isFalse();
    }

    @Test
    @DisplayName("값이 같으면 동등하다 (값 객체)")
    void 값이_같으면_동등() {
        assertThat(Money.won(500_000)).isEqualTo(Money.won(500_000));
    }
}
