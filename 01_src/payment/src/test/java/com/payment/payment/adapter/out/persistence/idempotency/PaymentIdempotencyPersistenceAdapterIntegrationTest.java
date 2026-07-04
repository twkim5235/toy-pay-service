package com.payment.payment.adapter.out.persistence.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.domain.IdempotencyStatus;
import com.payment.payment.domain.PaymentIdempotency;
import com.payment.payment.domain.PgCallStatus;
import com.payment.support.AbstractMySqlContainerTest;
import java.time.Instant;
import java.util.Optional;
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
@Import(PaymentIdempotencyPersistenceAdapter.class)
@DisplayName("payment_idempotency 영속 어댑터 통합테스트 (Testcontainers MySQL)")
class PaymentIdempotencyPersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {

    @Autowired PaymentIdempotencyRepository paymentIdempotencyRepository;

    // save 후 영속성 컨텍스트를 비워 실제 DB 라운드트립(SQL·매핑)을 검증한다.
    @Autowired TestEntityManager em;

    private static final Instant EXPIRED_AT = Instant.parse("2026-07-05T00:00:00Z");

    @Test
    @DisplayName("PENDING 멱등성을 save 후 (user_id, key)로 find하면 모든 필드가 그대로 복원된다")
    void save_후_find_라운드트립() {
        PaymentIdempotency saved = PaymentIdempotency.start(
                "idem-key-1", "user-1", "order-1", "pay-1", EXPIRED_AT);
        paymentIdempotencyRepository.save(saved);
        em.flush();
        em.clear();

        Optional<PaymentIdempotency> found =
                paymentIdempotencyRepository.find("user-1", "idem-key-1");

        assertThat(found).isPresent();
        PaymentIdempotency it = found.get();
        assertThat(it.idempotencyKey()).isEqualTo("idem-key-1");
        assertThat(it.userId()).isEqualTo("user-1");
        assertThat(it.orderId()).isEqualTo("order-1");
        assertThat(it.paymentId()).isEqualTo("pay-1");
        assertThat(it.status()).isEqualTo(IdempotencyStatus.PENDING);
        assertThat(it.pgCallStatus()).isEqualTo(PgCallStatus.NOT_CALLED);
        assertThat(it.pgIdempotencyKey()).isNull();
        assertThat(it.expiredAt()).isEqualTo(EXPIRED_AT);
    }

    @Test
    @DisplayName("markCalling으로 보존된 pg_idempotency_key와 CALLING 상태가 라운드트립으로 유지된다")
    void pg_멱등키_보존_라운드트립() {
        PaymentIdempotency it = PaymentIdempotency.start(
                "idem-key-2", "user-1", "order-2", "pay-2", EXPIRED_AT);
        it.markCalling("pay-2:alloc-9");
        paymentIdempotencyRepository.save(it);
        em.flush();
        em.clear();

        PaymentIdempotency found =
                paymentIdempotencyRepository.find("user-1", "idem-key-2").orElseThrow();
        assertThat(found.pgCallStatus()).isEqualTo(PgCallStatus.CALLING);
        assertThat(found.pgIdempotencyKey()).isEqualTo("pay-2:alloc-9");
    }

    @Test
    @DisplayName("같은 key라도 다른 user_id면 find는 빈 결과 (사용자 격리)")
    void 사용자_격리() {
        paymentIdempotencyRepository.save(PaymentIdempotency.start(
                "shared-key", "user-A", "order-A", "pay-A", EXPIRED_AT));
        em.flush();
        em.clear();

        assertThat(paymentIdempotencyRepository.find("user-B", "shared-key")).isEmpty();
    }

    @Test
    @DisplayName("존재하지 않는 멱등성 조회는 빈 결과")
    void 미존재_조회() {
        assertThat(paymentIdempotencyRepository.find("user-1", "no-such-key")).isEmpty();
    }
}