package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentIdempotencyTest {

    private static final Instant EXPIRES = Instant.parse("2026-05-26T00:00:00Z");

    @Test
    @DisplayName("COMPLETED는 기존 결과를 반환한다 (REPLAY)")
    void 완료된_키는_REPLAY() {
        PaymentIdempotency i = PaymentIdempotency.restore(
                "k", "u1", "ord", "PAY1", IdempotencyStatus.COMPLETED, PgCallStatus.SUCCESS, EXPIRES);

        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.REPLAY);
    }

    @Test
    @DisplayName("PENDING은 진행 중으로 본다 (IN_PROGRESS → 409)")
    void 진행중_키는_IN_PROGRESS() {
        PaymentIdempotency i = PaymentIdempotency.restore(
                "k", "u1", "ord", "PAY1", IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED, EXPIRES);

        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.IN_PROGRESS);
    }

    @Test
    @DisplayName("FAILED는 신규 재시도를 허용한다 (PROCEED_NEW, 설계 시나리오 15)")
    void 실패한_키는_PROCEED_NEW() {
        PaymentIdempotency i = PaymentIdempotency.restore(
                "k", "u1", "ord", null, IdempotencyStatus.FAILED, PgCallStatus.FAILED, EXPIRES);

        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.PROCEED_NEW);
    }

    @Test
    @DisplayName("start는 PENDING + NOT_CALLED + 주입된 만료시각으로 시작한다")
    void start는_PENDING으로_시작() {
        PaymentIdempotency i = PaymentIdempotency.start("k", "u1", "ord", "PAY1", EXPIRES);

        assertThat(i.status()).isEqualTo(IdempotencyStatus.PENDING);
        assertThat(i.pgCallStatus()).isEqualTo(PgCallStatus.NOT_CALLED);
        assertThat(i.expiredAt()).isEqualTo(EXPIRES);
    }

    @Test
    @DisplayName("complete는 COMPLETED + SUCCESS로 전이한다")
    void complete_전이() {
        PaymentIdempotency i = PaymentIdempotency.start("k", "u1", "ord", "PAY1", EXPIRES);

        i.complete();

        assertThat(i.status()).isEqualTo(IdempotencyStatus.COMPLETED);
        assertThat(i.pgCallStatus()).isEqualTo(PgCallStatus.SUCCESS);
    }

    @Test
    @DisplayName("fail은 FAILED로 전이한다")
    void fail_전이() {
        PaymentIdempotency i = PaymentIdempotency.start("k", "u1", "ord", "PAY1", EXPIRES);

        i.fail();

        assertThat(i.status()).isEqualTo(IdempotencyStatus.FAILED);
    }

    @Test
    @DisplayName("markPgStatus는 PG 호출 상태만 바꾼다 (UNKNOWN 등)")
    void markPgStatus_전이() {
        PaymentIdempotency i = PaymentIdempotency.start("k", "u1", "ord", "PAY1", EXPIRES);

        i.markPgStatus(PgCallStatus.UNKNOWN);

        assertThat(i.pgCallStatus()).isEqualTo(PgCallStatus.UNKNOWN);
        assertThat(i.status()).isEqualTo(IdempotencyStatus.PENDING);
    }
}
