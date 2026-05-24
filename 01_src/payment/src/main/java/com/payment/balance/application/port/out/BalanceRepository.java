package com.payment.balance.application.port.out;

import com.payment.balance.domain.UserBalance;
import java.util.Optional;

/**
 * 잔액 영속성 out 포트 (설계 7-2 user_balance).
 *
 * <p>{@code findByUserIdWithPessimisticLock}는 비관적 쓰기 락(SELECT ... FOR UPDATE)으로 조회한다.
 * 결제 트랜잭션은 <b>잔액 → 한도 누적</b> 고정 순서로 락을 잡아 데드락을 회피한다(설계 9-2).
 * 트랜잭션 경계는 호출하는 유스케이스가 소유하며, 본 어댑터는 트랜잭션에 참여만 한다.
 */
public interface BalanceRepository {

    /** 락 없이 조회 (잔액 조회 핫패스용). */
    Optional<UserBalance> findByUserId(String userId);

    /** 비관적 쓰기 락으로 조회 (차감/충전 전 호출, 설계 9-3 트랜잭션1). */
    Optional<UserBalance> findByUserIdWithPessimisticLock(String userId);

    void save(UserBalance balance);
}
