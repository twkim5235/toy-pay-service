package com.payment.balance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UserDailyUsage 일일 결제 누적 도메인")
class UserDailyUsageTest {

    private static final UserDailyLimit LIMIT = new UserDailyLimit("user-1", Money.won(1_000_000));
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 24);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Test
    @DisplayName("결제하면 일일 누적이 증가한다")
    void 결제하면_누적_증가() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(300_000), TODAY);

        usage.use(Money.won(200_000), LIMIT, TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(500_000));
    }

    @Test
    @DisplayName("누적이 한도와 정확히 같아지는 결제는 허용한다 (경계)")
    void 한도와_같으면_허용() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(900_000), TODAY);

        usage.use(Money.won(100_000), LIMIT, TODAY);

        assertThat(usage.usedAmount()).isEqualTo(LIMIT.value());
    }

    @Test
    @DisplayName("한도를 초과하는 결제는 DAILY_LIMIT_EXCEEDED 예외")
    void 한도초과면_예외() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(900_000), TODAY);

        assertThatThrownBy(() -> usage.use(Money.won(100_001), LIMIT, TODAY))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED));
    }

    @Test
    @DisplayName("한도 초과로 거부되면 누적은 변하지 않는다")
    void 한도초과_시_누적_불변() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(900_000), TODAY);

        assertThatThrownBy(() -> usage.use(Money.won(200_000), LIMIT, TODAY))
                .isInstanceOf(BusinessException.class);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(900_000));
    }

    @Test
    @DisplayName("자정이 지나면(마지막 리셋이 과거) 누적이 0으로 리셋된 뒤 결제된다 (lazy 리셋)")
    void 자정_지나면_lazy_리셋() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(900_000), YESTERDAY);

        usage.use(Money.won(100_000), LIMIT, TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(100_000));
        assertThat(usage.lastResetDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("어제 누적이 한도에 가까워도 오늘 리셋되어 한도 검사를 통과한다")
    void 어제_누적_많아도_오늘_리셋후_통과() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(1_000_000), YESTERDAY);

        usage.use(Money.won(700_000), LIMIT, TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(700_000));
    }

    @Test
    @DisplayName("같은 날이면 리셋하지 않고 누적을 유지한다")
    void 같은_날이면_누적_유지() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(300_000), TODAY);

        usage.use(Money.won(100_000), LIMIT, TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(400_000));
    }

    @Test
    @DisplayName("rollback하면 누적이 차감된다")
    void rollback하면_누적_차감() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(500_000), TODAY);

        usage.rollback(Money.won(200_000), TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.won(300_000));
    }

    @Test
    @DisplayName("자정 넘어 리셋된 뒤 rollback해도 누적이 음수가 되지 않는다 (0으로 클램프)")
    void 리셋후_rollback은_0으로_클램프() {
        UserDailyUsage usage = new UserDailyUsage("user-1", Money.won(500_000), YESTERDAY);

        usage.rollback(Money.won(200_000), TODAY);

        assertThat(usage.usedAmount()).isEqualTo(Money.ZERO);
        assertThat(usage.lastResetDate()).isEqualTo(TODAY);
    }
}
