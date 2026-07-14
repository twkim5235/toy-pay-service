package com.payment.balance.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.balance.application.port.out.BalanceRepository;
import com.payment.balance.application.port.out.DailyLimitRepository;
import com.payment.balance.application.port.out.DailyUsageRepository;
import com.payment.balance.domain.UserBalance;
import com.payment.balance.domain.UserDailyLimit;
import com.payment.balance.domain.UserDailyUsage;
import com.payment.common.Money;
import com.payment.support.AbstractMySqlContainerTest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// Kafka autoconfig를 제외하지 않는다 — KafkaEventPublisher가 KafkaTemplate 빈을 요구하며,
// 템플릿은 첫 send까지 브로커에 접속하지 않아 브로커 없이도 컨텍스트가 뜬다.
@SpringBootTest
@DisplayName("balance 영속 어댑터 통합테스트 (Testcontainers MySQL)")
class BalancePersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {

    @Autowired BalanceRepository balanceRepository;
    @Autowired DailyUsageRepository dailyUsageRepository;
    @Autowired DailyLimitRepository dailyLimitRepository;

    // 어댑터가 애그리거트별 하위 패키지로 분리돼 JPA 리포지토리는 package-private(외부 비가시) →
    // 통합테스트는 public 포트 + JdbcTemplate truncate로만 동작한다.
    @Autowired JdbcTemplate jdbcTemplate;

    @Autowired PlatformTransactionManager txManager;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
        jdbcTemplate.execute("DELETE FROM user_balance");
        jdbcTemplate.execute("DELETE FROM user_daily_usage");
        jdbcTemplate.execute("DELETE FROM user_daily_limit");
    }

    @Test
    @DisplayName("잔액을 저장하고 다시 읽으면 같은 값이다 (엔티티-스키마 정합성)")
    void 잔액_라운드트립() {
        balanceRepository.save(new UserBalance("user-1", Money.won(50_000)));

        UserBalance loaded = balanceRepository.findByUserId("user-1").orElseThrow();

        assertThat(loaded.userId()).isEqualTo("user-1");
        assertThat(loaded.balance()).isEqualTo(Money.won(50_000));
    }

    @Test
    @DisplayName("일일 누적(누적액 + 리셋일)을 저장하고 다시 읽으면 같은 값이다")
    void 일일누적_라운드트립() {
        LocalDate today = LocalDate.of(2026, 5, 24);
        dailyUsageRepository.save(new UserDailyUsage("user-1", Money.won(30_000), today));

        UserDailyUsage loaded = dailyUsageRepository.findByUserId("user-1").orElseThrow();

        assertThat(loaded.usedAmount()).isEqualTo(Money.won(30_000));
        assertThat(loaded.lastResetDate()).isEqualTo(today);
    }

    @Test
    @DisplayName("일일 한도를 저장하고 다시 읽으면 같은 값이다")
    void 일일한도_라운드트립() {
        dailyLimitRepository.save(new UserDailyLimit("user-1", Money.won(1_000_000)));

        UserDailyLimit loaded = dailyLimitRepository.findByUserId("user-1").orElseThrow();

        assertThat(loaded.value()).isEqualTo(Money.won(1_000_000));
    }

    @Test
    @DisplayName("비관적 락(FOR UPDATE)이 동시 차감을 직렬화해 lost update를 막는다")
    void 비관적락_동시차감_lost_update_방지() throws Exception {
        String userId = "concurrent-user";
        balanceRepository.save(new UserBalance(userId, Money.won(1_000)));

        int threads = 8;
        int deductions = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < deductions; i++) {
            futures.add(pool.submit(() -> tx.executeWithoutResult(status -> {
                UserBalance balance = balanceRepository.findByUserIdWithPessimisticLock(userId).orElseThrow();
                balance.deduct(Money.won(1));
                balanceRepository.save(balance);
            })));
        }
        for (Future<?> future : futures) {
            future.get(); // 예외(데드락·타임아웃 등) 있으면 여기서 전파
        }
        pool.shutdown();

        UserBalance result = balanceRepository.findByUserId(userId).orElseThrow();
        // 락이 없으면 read-modify-write 경합으로 차감이 유실되어 잔액이 950보다 커진다.
        assertThat(result.balance()).isEqualTo(Money.won(1_000 - deductions));
    }
}
