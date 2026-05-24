package com.payment.balance.adapter.out.persistence.balance;

import com.payment.balance.application.port.out.BalanceRepository;
import com.payment.balance.domain.UserBalance;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class BalancePersistenceAdapter implements BalanceRepository {

    private final UserBalanceJpaRepository jpaRepository;

    @Override
    public Optional<UserBalance> findByUserId(String userId) {
        return jpaRepository.findById(userId).map(UserBalanceJpaEntity::toDomain);
    }

    @Override
    public Optional<UserBalance> findByUserIdWithPessimisticLock(String userId) {
        return jpaRepository.findByUserIdWithPessimisticLock(userId).map(UserBalanceJpaEntity::toDomain);
    }

    @Override
    public void save(UserBalance balance) {
        jpaRepository.save(UserBalanceJpaEntity.from(balance));
    }
}
