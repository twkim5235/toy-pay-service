package com.payment.balance.adapter.out.persistence.usage;

import com.payment.balance.application.port.out.DailyUsageRepository;
import com.payment.balance.domain.UserDailyUsage;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class DailyUsagePersistenceAdapter implements DailyUsageRepository {

    private final UserDailyUsageJpaRepository jpaRepository;

    @Override
    public Optional<UserDailyUsage> findByUserId(String userId) {
        return jpaRepository.findById(userId).map(UserDailyUsageJpaEntity::toDomain);
    }

    @Override
    public Optional<UserDailyUsage> findByUserIdWithPessimisticLock(String userId) {
        return jpaRepository.findByUserIdWithPessimisticLock(userId).map(UserDailyUsageJpaEntity::toDomain);
    }

    @Override
    public void save(UserDailyUsage usage) {
        jpaRepository.save(UserDailyUsageJpaEntity.from(usage));
    }
}
