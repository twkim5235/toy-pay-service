package com.payment.balance.adapter.out.persistence.balance;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserBalanceJpaRepository extends JpaRepository<UserBalanceJpaEntity, String> {

    /** SELECT ... FOR UPDATE (비관적 쓰기 락, 설계 9-3). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from UserBalanceJpaEntity b where b.userId = :userId")
    Optional<UserBalanceJpaEntity> findByUserIdWithPessimisticLock(@Param("userId") String userId);
}
