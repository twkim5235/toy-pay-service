package com.payment.balance.adapter.out.persistence.usage;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserDailyUsageJpaRepository extends JpaRepository<UserDailyUsageJpaEntity, String> {

    /** SELECT ... FOR UPDATE (비관적 쓰기 락, 설계 9-3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserDailyUsageJpaEntity u where u.userId = :userId")
    Optional<UserDailyUsageJpaEntity> findByUserIdWithPessimisticLock(@Param("userId") String userId);
}
