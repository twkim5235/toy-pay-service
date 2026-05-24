package com.payment.balance.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserDailyLimit 일일 한도 도메인")
class UserDailyLimitTest {

    private static final Money LIMIT = Money.won(1_000_000);

    @Test
    @DisplayName("설정된 한도 값을 보유한다")
    void 한도값_보유() {
        UserDailyLimit limit = new UserDailyLimit("user-1", LIMIT);

        assertThat(limit.value()).isEqualTo(LIMIT);
    }

    @Test
    @DisplayName("한도보다 큰 누적 예상치는 초과로 판정한다")
    void 한도초과_판정() {
        UserDailyLimit limit = new UserDailyLimit("user-1", LIMIT);

        assertThat(limit.wouldExceed(Money.won(1_000_001))).isTrue();
    }

    @Test
    @DisplayName("한도와 정확히 같은 누적 예상치는 초과가 아니다 (경계)")
    void 한도와_같으면_초과_아님() {
        UserDailyLimit limit = new UserDailyLimit("user-1", LIMIT);

        assertThat(limit.wouldExceed(LIMIT)).isFalse();
    }
}
