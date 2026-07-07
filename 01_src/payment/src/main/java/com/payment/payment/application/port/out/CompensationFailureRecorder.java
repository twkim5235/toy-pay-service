package com.payment.payment.application.port.out;

import com.payment.common.Money;

/**
 * 보상 트랜잭션 실패 기록 (driven 포트, 설계 6-4 compensating_transaction_failures). 수동 복구 채널.
 *
 * <p>payment-core는 PG 회수(환불)를 보류하므로, 이미 청구된 카드의 회수 필요분을 {@code PG_REFUND_CALL}로
 * 남겨 2차 환불 도메인이 회복하게 한다. 내부 원복(잔액/한도) 자체가 실패하면 {@code BALANCE_ROLLBACK}/
 * {@code USAGE_ROLLBACK}로 기록 + 알람.
 */
public interface CompensationFailureRecorder {

    void record(String paymentId, FailureType type, Money amount);

    enum FailureType { BALANCE_ROLLBACK, USAGE_ROLLBACK, PG_REFUND_CALL }
}
