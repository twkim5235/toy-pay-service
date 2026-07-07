package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAllocationTest {

    @Test
    @DisplayName("BALANCE 수단은 method_id 없이 생성되고 상태는 PENDING이다")
    void 잔액수단은_method_id가_없다() {
        PaymentAllocation a = PaymentAllocation.balance(Money.won(30_000));

        assertThat(a.methodType()).isEqualTo(PaymentMethodType.BALANCE);
        assertThat(a.methodId()).isNull();
        assertThat(a.amount()).isEqualTo(Money.won(30_000));
        assertThat(a.status()).isEqualTo(AllocationStatus.PENDING);
    }

    @Test
    @DisplayName("외부 수단(CARD)은 method_id가 없으면 INVALID_REQUEST")
    void 외부수단은_method_id가_필수다() {
        assertThatThrownBy(() -> PaymentAllocation.external(PaymentMethodType.CARD, null, Money.won(1_000)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("분배 금액이 0이면 INVALID_REQUEST (경계값)")
    void 분배금액이_0이면_거부() {
        assertThatThrownBy(() -> PaymentAllocation.balance(Money.ZERO))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }

    @Test
    @DisplayName("settle 시 SETTLED로 전이하고 pg_transaction_id를 보존한다")
    void settle하면_SETTLED와_pg거래id를_보존() {
        PaymentAllocation a = PaymentAllocation.external(PaymentMethodType.CARD, "CARD_a", Money.won(700_000));

        a.settle("PG_tx_1");

        assertThat(a.status()).isEqualTo(AllocationStatus.SETTLED);
        assertThat(a.pgTransactionId()).isEqualTo("PG_tx_1");
    }

    @Test
    @DisplayName("fail 시 FAILED로 전이한다")
    void fail하면_FAILED() {
        PaymentAllocation a = PaymentAllocation.balance(Money.won(1_000));

        a.fail();

        assertThat(a.status()).isEqualTo(AllocationStatus.FAILED);
    }

    @Test
    @DisplayName("isExternal은 BALANCE만 false")
    void isExternal은_BALANCE만_false() {
        assertThat(PaymentAllocation.balance(Money.won(1_000)).isExternal()).isFalse();
        assertThat(PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(1_000)).isExternal()).isTrue();
    }
}
