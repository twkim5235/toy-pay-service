package com.payment.balance.adapter.out.persistence.limit;

import org.springframework.data.jpa.repository.JpaRepository;

interface UserDailyLimitJpaRepository extends JpaRepository<UserDailyLimitJpaEntity, String> {
    // 한도는 읽기 전용 — 기본 findById(=findByUserId) 사용, 락 불필요.
}
