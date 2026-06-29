package com.payment.balance.adapter.out.persistence.history;

import com.payment.balance.application.port.out.BalanceHistoryRepository;
import com.payment.balance.domain.BalanceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class BalanceHistoryPersistenceAdapter implements BalanceHistoryRepository {

    private final BalanceHistoryJpaRepository jpaRepository;

    @Override
    public void append(BalanceHistory history) {
        jpaRepository.save(BalanceHistoryJpaEntity.from(history));
    }
}
