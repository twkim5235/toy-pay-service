package com.payment.payment.application.port.out;

import com.payment.common.Money;
import com.payment.payment.domain.PaymentMethodType;

/**
 * PG 청구 (driven 포트, 설계 6-1). <b>트랜잭션 밖</b>에서 호출해 락 보유 시간을 최소화한다(설계 9-3).
 *
 * <p>환불(refund)은 본 포트에 없다 — 시나리오 22/23의 PG 회수는 2차 환불 도메인 소관이며, payment-core는
 * 내부 잔액·한도 원복만 수행하고 회수는 보류한다(compensating_transaction_failures 기록).
 */
public interface PgPort {

    PgChargeResult charge(PgChargeRequest request);

    record PgChargeRequest(String pgIdempotencyKey, PaymentMethodType methodType, String methodId, Money amount) {}

    record PgChargeResult(Outcome outcome, String pgTransactionId, String declineReason) {

        public enum Outcome { APPROVED, DECLINED, TIMEOUT }

        public static PgChargeResult approved(String pgTransactionId) {
            return new PgChargeResult(Outcome.APPROVED, pgTransactionId, null);
        }

        public static PgChargeResult declined(String declineReason) {
            return new PgChargeResult(Outcome.DECLINED, null, declineReason);
        }

        public static PgChargeResult timeout() {
            return new PgChargeResult(Outcome.TIMEOUT, null, null);
        }
    }
}
