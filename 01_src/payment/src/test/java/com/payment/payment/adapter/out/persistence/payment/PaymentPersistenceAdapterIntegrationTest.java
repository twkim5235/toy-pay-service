package com.payment.payment.adapter.out.persistence.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.payment.common.Money;
import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.Payment;
import com.payment.payment.domain.PaymentAllocation;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import com.payment.support.AbstractMySqlContainerTest;
import java.time.Instant;
import java.util.List;
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
@Import(PaymentPersistenceAdapter.class)
@DisplayName("payments + payment_allocation 애그리거트 영속 어댑터 통합테스트 (Testcontainers MySQL)")
class PaymentPersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {

    @Autowired PaymentRepository paymentRepository;

    // save 후 영속성 컨텍스트를 비워 실제 DB 라운드트립(애그리거트 재조립)을 검증한다.
    @Autowired TestEntityManager em;

    private static PaymentAllocation allocationOf(List<PaymentAllocation> allocations,
            PaymentMethodType type) {
        return allocations.stream().filter(a -> a.methodType() == type).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("분할 결제(BALANCE + CARD)를 save 후 findById하면 결제와 allocation이 통째로 복원된다")
    void 애그리거트_save_후_findById_라운드트립() {
        Payment payment = Payment.create("user-1", "order-1", Money.won(30_000),
                List.of(PaymentAllocation.balance(Money.won(10_000)),
                        PaymentAllocation.external(PaymentMethodType.CARD, "card-9", Money.won(20_000))));
        paymentRepository.save(payment);
        em.flush();
        em.clear();

        Optional<Payment> found = paymentRepository.findById(payment.id());

        assertThat(found).isPresent();
        Payment it = found.get();
        assertThat(it.id()).isEqualTo(payment.id());
        assertThat(it.userId()).isEqualTo("user-1");
        assertThat(it.orderId()).isEqualTo("order-1");
        assertThat(it.totalAmount()).isEqualTo(Money.won(30_000));
        assertThat(it.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(it.paidAt()).isNull();
        assertThat(it.allocations()).hasSize(2);

        PaymentAllocation balance = allocationOf(it.allocations(), PaymentMethodType.BALANCE);
        assertThat(balance.amount()).isEqualTo(Money.won(10_000));
        assertThat(balance.methodId()).isNull();
        assertThat(balance.status()).isEqualTo(AllocationStatus.PENDING);
        assertThat(balance.pgTransactionId()).isNull();

        PaymentAllocation card = allocationOf(it.allocations(), PaymentMethodType.CARD);
        assertThat(card.amount()).isEqualTo(Money.won(20_000));
        assertThat(card.methodId()).isEqualTo("card-9");
    }

    @Test
    @DisplayName("BALANCE 단독 결제(allocation 1개, PG 없음)를 save 후 findById하면 그대로 복원된다")
    void 잔액단독_save_후_findById_라운드트립() {
        Payment payment = Payment.create("user-9", "order-9", Money.won(15_000),
                List.of(PaymentAllocation.balance(Money.won(15_000))));
        paymentRepository.save(payment);
        em.flush();
        em.clear();

        Payment it = paymentRepository.findById(payment.id()).orElseThrow();
        assertThat(it.totalAmount()).isEqualTo(Money.won(15_000));
        assertThat(it.allocations()).hasSize(1);
        PaymentAllocation only = it.allocations().get(0);
        assertThat(only.methodType()).isEqualTo(PaymentMethodType.BALANCE);
        assertThat(only.methodId()).isNull();
        assertThat(only.amount()).isEqualTo(Money.won(15_000));
        assertThat(only.status()).isEqualTo(AllocationStatus.PENDING);
        assertThat(only.pgTransactionId()).isNull();
    }

    @Test
    @DisplayName("PAID 확정 후 재-save하면 결제 상태·paidAt과 allocation의 SETTLED·pg_transaction_id가 갱신된다")
    void 상태전이_재save_반영() {
        Instant paidAt = Instant.parse("2026-07-04T12:00:00Z");
        Payment payment = Payment.create("user-2", "order-2", Money.won(20_000),
                List.of(PaymentAllocation.external(PaymentMethodType.CARD, "card-1", Money.won(20_000))));
        paymentRepository.save(payment);

        payment.allocations().get(0).settle("pg-tx-777");
        payment.markPaid(paidAt);
        paymentRepository.save(payment);
        em.flush();
        em.clear();

        Payment it = paymentRepository.findById(payment.id()).orElseThrow();
        assertThat(it.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(it.paidAt()).isEqualTo(paidAt);
        PaymentAllocation card = it.allocations().get(0);
        assertThat(card.status()).isEqualTo(AllocationStatus.SETTLED);
        assertThat(card.pgTransactionId()).isEqualTo("pg-tx-777");
    }

    @Test
    @DisplayName("존재하지 않는 결제 조회는 빈 결과")
    void 미존재_조회() {
        assertThat(paymentRepository.findById("no-such-id")).isEmpty();
    }
}
