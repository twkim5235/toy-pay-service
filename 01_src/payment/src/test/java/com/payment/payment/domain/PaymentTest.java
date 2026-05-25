package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private PaymentAllocation balance(long v) {
        return PaymentAllocation.balance(Money.won(v));
    }

    private PaymentAllocation card(String methodId, long v) {
        return PaymentAllocation.external(PaymentMethodType.CARD, methodId, Money.won(v));
    }

    @Test
    @DisplayName("메인 시나리오: 잔액 30만 + 카드 70만 분할 결제가 생성된다")
    void 분할결제_생성() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(balance(300_000), card("c1", 700_000)));

        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.allocations()).hasSize(2);
        assertThat(p.userId()).isEqualTo("u1");
        assertThat(p.orderId()).isEqualTo("ord1");
    }

    @Test
    @DisplayName("분배 합이 total_amount와 다르면 INVALID_REQUEST")
    void 분배합_불일치_거부() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(balance(300_000), card("c1", 600_000))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("BALANCE allocation은 최대 1개")
    void 잔액수단_최대1개() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(2_000),
                List.of(balance(1_000), balance(1_000))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("외부 method_id가 중복되면 거부")
    void method_id_중복_거부() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(2_000),
                List.of(card("c1", 1_000), card("c1", 1_000))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("allocations가 비어 있으면 거부")
    void allocations_빈_경우_거부() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(1_000), List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("balanceAmount와 externalAllocations를 분리해 노출한다")
    void 잔액금액과_외부분배_분리() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(balance(300_000), card("c1", 700_000)));

        assertThat(p.balanceAmount()).isEqualTo(Money.won(300_000));
        assertThat(p.externalAllocations()).hasSize(1);
    }

    @Test
    @DisplayName("BALANCE 없는 결제의 balanceAmount는 0")
    void 잔액수단_없으면_balanceAmount는_0() {
        Payment p = Payment.create("u1", "ord1", Money.won(700_000), List.of(card("c1", 700_000)));

        assertThat(p.balanceAmount()).isEqualTo(Money.ZERO);
        assertThat(p.externalAllocations()).hasSize(1);
    }

    @Test
    @DisplayName("markPaid는 PAID로 전이하고 paidAt을 설정한다")
    void markPaid_전이() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000), List.of(balance(1_000)));

        p.markPaid(Instant.parse("2026-05-25T00:00:00Z"));

        assertThat(p.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.paidAt()).isEqualTo(Instant.parse("2026-05-25T00:00:00Z"));
    }

    @Test
    @DisplayName("markFailed / markFailedRefunded 전이")
    void 실패_전이() {
        Payment p1 = Payment.create("u1", "ord1", Money.won(1_000), List.of(balance(1_000)));
        p1.markFailed();
        assertThat(p1.status()).isEqualTo(PaymentStatus.FAILED);

        Payment p2 = Payment.create("u1", "ord2", Money.won(1_000), List.of(balance(1_000)));
        p2.markFailedRefunded();
        assertThat(p2.status()).isEqualTo(PaymentStatus.FAILED_REFUNDED);
    }
}
