package com.payment.payment.adapter.out.persistence.compensation;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.Money;
import com.payment.payment.application.port.out.CompensationFailureRecorder;
import com.payment.payment.application.port.out.CompensationFailureRecorder.FailureType;
import com.payment.support.AbstractMySqlContainerTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Import(CompensationFailurePersistenceAdapter.class)
@DisplayName("compensating_transaction_failures 영속 어댑터 통합테스트 (Testcontainers MySQL)")
class CompensationFailurePersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {

    @Autowired CompensationFailureRecorder compensationFailureRecorder;

    // INSERT-only append만 노출(read 없음) → 검증은 네이티브 조회로 한다.
    @Autowired TestEntityManager em;

    @SuppressWarnings("unchecked")
    private List<Object[]> rows() {
        em.flush();
        em.clear();
        return em.getEntityManager()
                .createNativeQuery(
                        "SELECT payment_id, charge_id, failure_type, amount "
                                + "FROM compensating_transaction_failures ORDER BY id")
                .getResultList();
    }

    @Test
    @DisplayName("PG 회수 필요분을 record하면 payment_id·failure_type·amount로 한 행 INSERT (charge_id는 NULL)")
    void record하면_한행_INSERT() {
        compensationFailureRecorder.record("pay-1", FailureType.PG_REFUND_CALL, Money.won(20_000));

        List<Object[]> rows = rows();
        assertThat(rows).hasSize(1);
        Object[] row = rows.get(0);
        assertThat(row[0]).isEqualTo("pay-1");                     // payment_id
        assertThat(row[1]).isNull();                               // charge_id (payment-core는 항상 NULL)
        assertThat(row[2]).isEqualTo("PG_REFUND_CALL");            // failure_type (enum 이름 그대로)
        assertThat(((Number) row[3]).longValue()).isEqualTo(20_000L); // amount
    }

    @Test
    @DisplayName("INSERT-only: 서로 다른 실패 유형을 두 번 record하면 행이 2개 쌓인다")
    void record는_누적된다() {
        compensationFailureRecorder.record("pay-2", FailureType.BALANCE_ROLLBACK, Money.won(5_000));
        compensationFailureRecorder.record("pay-2", FailureType.USAGE_ROLLBACK, Money.won(5_000));

        List<Object[]> rows = rows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)[2]).isEqualTo("BALANCE_ROLLBACK");
        assertThat(rows.get(1)[2]).isEqualTo("USAGE_ROLLBACK");
    }
}
