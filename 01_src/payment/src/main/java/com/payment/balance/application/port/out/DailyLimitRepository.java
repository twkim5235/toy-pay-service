package com.payment.balance.application.port.out;

import com.payment.balance.domain.UserDailyLimit;
import java.util.Optional;

/**
 * 일일 한도 영속성 out 포트 (설계 7-2 user_daily_limit).
 *
 * <p>한도는 결제 흐름에서 읽기 전용(차감되지 않음)이라 락을 잡지 않는다(설계 9-3은 잔액·누적 두 row만 락).
 */
public interface DailyLimitRepository {

    Optional<UserDailyLimit> findByUserId(String userId);

    void save(UserDailyLimit limit);
}
