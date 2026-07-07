package com.payment.balance.adapter.out.persistence.history;

import org.springframework.data.jpa.repository.JpaRepository;

interface BalanceHistoryJpaRepository extends JpaRepository<BalanceHistoryJpaEntity, Long> {
}
