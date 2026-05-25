package com.payment.balance.application.port.out;

import com.payment.balance.domain.BalanceHistory;

/**
 * 잔액 변경 감사 로그 append (설계 7-2 balance_history). INSERT-only.
 *
 * <p>잔액 차감·원복·충전과 같은 트랜잭션 안에서 호출되어 정합성을 함께 보장한다. 호출 트랜잭션에 참여만 한다.
 */
public interface BalanceHistoryRepository {

    void append(BalanceHistory history);
}
