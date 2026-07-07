package com.payment.payment.application.port.out;

import com.payment.payment.domain.PaymentIdempotency;
import java.util.Optional;

/**
 * 결제 멱등성 영속성 (driven 포트, 설계 7-3). 조회는 {@code (user_id, idempotency_key)} 조합으로
 * 사용자 격리를 보장한다(다른 사용자가 같은 키를 보내도 별개 처리).
 */
public interface PaymentIdempotencyRepository {

    Optional<PaymentIdempotency> find(String userId, String idempotencyKey);

    void save(PaymentIdempotency idempotency);
}
