package com.payment.payment.adapter.out.pg;

import com.payment.payment.application.port.out.PgPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mock PG 어댑터 (설계 6-3). 실제 PG 연동 전까지 methodId 매직 규약으로 결과를 결정한다 —
 * {@code decline*}은 거절, {@code timeout*}은 불확실(TIMEOUT), 그 외는 승인.
 * 테스트·시연에서 세 갈래(confirm/compensate/보정 대상)를 모두 재현하기 위한 규약이다.
 */
@Component
class MockPgAdapter implements PgPort {

    @Override
    public PgChargeResult charge(PgChargeRequest request) {
        String methodId = request.methodId();
        if (methodId.startsWith("decline")) {
            return PgChargeResult.declined("카드사 거절 (mock: decline 접두사)");
        }
        if (methodId.startsWith("timeout")) {
            return PgChargeResult.timeout();
        }
        return PgChargeResult.approved(UUID.randomUUID().toString());
    }
}
