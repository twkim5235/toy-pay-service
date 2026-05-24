package com.payment.balance.adapter.out.persistence.limit;

import com.payment.balance.application.port.out.DailyLimitRepository;
import com.payment.balance.domain.UserDailyLimit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DailyLimitPersistenceAdapter implements DailyLimitRepository {

    private final UserDailyLimitJpaRepository jpaRepository;

    @Override
    public Optional<UserDailyLimit> findByUserId(String userId) {
        return jpaRepository.findById(userId).map(UserDailyLimitJpaEntity::toDomain);
    }

    @Override
    public void save(UserDailyLimit limit) {
        jpaRepository.save(UserDailyLimitJpaEntity.from(limit));
    }
}
