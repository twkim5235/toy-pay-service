package com.payment.payment.adapter.out.pg;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.Money;
import com.payment.payment.application.port.out.PgPort.PgChargeRequest;
import com.payment.payment.application.port.out.PgPort.PgChargeResult;
import com.payment.payment.application.port.out.PgPort.PgChargeResult.Outcome;
import com.payment.payment.domain.PaymentMethodType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MockPgAdapter 단위테스트 — methodId 매직 규약으로 결과 결정")
class MockPgAdapterTest {

    private final MockPgAdapter adapter = new MockPgAdapter();

    private static PgChargeRequest request(String methodId) {
        return new PgChargeRequest("pay-1:alloc-1", PaymentMethodType.CARD, methodId, Money.won(20_000));
    }

    @Test
    @DisplayName("일반 methodId는 APPROVED — pg_transaction_id 발급, declineReason 없음")
    void 일반_methodId는_승인() {
        PgChargeResult result = adapter.charge(request("card-9"));

        assertThat(result.outcome()).isEqualTo(Outcome.APPROVED);
        assertThat(result.pgTransactionId()).isNotBlank();
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("methodId가 'decline'으로 시작하면 DECLINED — 사유 있음, 거래 ID 없음")
    void decline_접두사는_거절() {
        PgChargeResult result = adapter.charge(request("decline-stolen-card"));

        assertThat(result.outcome()).isEqualTo(Outcome.DECLINED);
        assertThat(result.declineReason()).isNotBlank();
        assertThat(result.pgTransactionId()).isNull();
    }

    @Test
    @DisplayName("methodId가 'timeout'으로 시작하면 TIMEOUT — 거래 ID·사유 모두 없음(불확실)")
    void timeout_접두사는_타임아웃() {
        PgChargeResult result = adapter.charge(request("timeout-1"));

        assertThat(result.outcome()).isEqualTo(Outcome.TIMEOUT);
        assertThat(result.pgTransactionId()).isNull();
        assertThat(result.declineReason()).isNull();
    }

    @Test
    @DisplayName("APPROVED의 pg_transaction_id는 호출마다 서로 다르다")
    void 승인_거래ID는_매번_유니크() {
        String tx1 = adapter.charge(request("card-1")).pgTransactionId();
        String tx2 = adapter.charge(request("card-2")).pgTransactionId();

        assertThat(tx1).isNotEqualTo(tx2);
    }
}
