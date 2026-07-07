package com.payment.balance.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.balance.application.port.out.BalanceHistoryRepository;
import com.payment.balance.domain.BalanceHistory;
import com.payment.common.Money;
import com.payment.support.AbstractMySqlContainerTest;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@DisplayName("balance_history 영속 어댑터 통합테스트 (Testcontainers MySQL)")
class BalanceHistoryPersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {

    @Autowired BalanceHistoryRepository balanceHistoryRepository;

    // 포트는 INSERT-only append만 노출(read 없음) → 검증은 JdbcTemplate 직접 조회로 한다.
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM balance_history");
    }

    @Test
    @DisplayName("결제 이력을 append하면 음수 변화량·payment_id로 한 행이 INSERT된다 (charge_id는 NULL)")
    void 결제이력_append() {
        balanceHistoryRepository.append(
                BalanceHistory.payment("user-1", Money.won(30_000), Money.won(70_000), "pay-1"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM balance_history");
        assertThat(row.get("user_id")).isEqualTo("user-1");
        assertThat(row.get("action")).isEqualTo("PAYMENT");
        assertThat(((Number) row.get("amount_change")).longValue()).isEqualTo(-30_000L);
        assertThat(((Number) row.get("balance_after")).longValue()).isEqualTo(70_000L);
        assertThat(row.get("payment_id")).isEqualTo("pay-1");
        assertThat(row.get("charge_id")).isNull();
    }

    @Test
    @DisplayName("충전 이력을 append하면 양수 변화량·charge_id로 INSERT되고 payment_id는 NULL이다")
    void 충전이력_append() {
        balanceHistoryRepository.append(
                BalanceHistory.charge("user-2", Money.won(50_000), Money.won(120_000), "chg-1"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM balance_history");
        assertThat(row.get("user_id")).isEqualTo("user-2");
        assertThat(row.get("action")).isEqualTo("CHARGE");
        assertThat(((Number) row.get("amount_change")).longValue()).isEqualTo(50_000L);
        assertThat(((Number) row.get("balance_after")).longValue()).isEqualTo(120_000L);
        assertThat(row.get("charge_id")).isEqualTo("chg-1");
        assertThat(row.get("payment_id")).isNull();
    }

    @Test
    @DisplayName("원복 이력을 append하면 양수 변화량·ROLLBACK으로 INSERT된다")
    void 원복이력_append() {
        balanceHistoryRepository.append(
                BalanceHistory.rollback("user-3", Money.won(30_000), Money.won(100_000), "pay-9"));

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM balance_history");
        assertThat(row.get("action")).isEqualTo("ROLLBACK");
        assertThat(((Number) row.get("amount_change")).longValue()).isEqualTo(30_000L);
        assertThat(row.get("payment_id")).isEqualTo("pay-9");
    }

    @Test
    @DisplayName("INSERT-only: 같은 사용자의 변경을 두 번 append하면 행이 2개 쌓인다 (id 자동 증가)")
    void append는_누적된다() {
        balanceHistoryRepository.append(
                BalanceHistory.charge("user-4", Money.won(10_000), Money.won(10_000), "chg-1"));
        balanceHistoryRepository.append(
                BalanceHistory.payment("user-4", Money.won(4_000), Money.won(6_000), "pay-1"));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM balance_history WHERE user_id = 'user-4'", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
