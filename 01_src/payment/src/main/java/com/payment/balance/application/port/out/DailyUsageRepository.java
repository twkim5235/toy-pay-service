package com.payment.balance.application.port.out;

import com.payment.balance.domain.UserDailyUsage;
import java.util.Optional;

/**
 * 일일 결제 누적 영속성 out 포트 (설계 7-2 user_daily_usage).
 *
 * <p>결제 트랜잭션에서 잔액 다음(고정 순서 두 번째)으로 비관적 쓰기 락을 잡는다(설계 9-2).
 */
public interface DailyUsageRepository {

    Optional<UserDailyUsage> findByUserId(String userId);

    /** 비관적 쓰기 락으로 조회. 잔액 락을 잡은 뒤 호출해야 한다(고정 순서). */
    Optional<UserDailyUsage> findByUserIdWithPessimisticLock(String userId);

    void save(UserDailyUsage usage);
}
