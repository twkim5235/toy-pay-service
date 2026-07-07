package com.payment.payment.application.port.out;

import com.payment.payment.domain.Payment;
import java.util.Optional;

/**
 * 결제 영속성 (driven 포트, 설계 7-1). {@code save}는 payment와 allocation을 함께 upsert하고,
 * {@code findById}는 allocation까지 모아 애그리거트를 재구성한다.
 */
public interface PaymentRepository {

    void save(Payment payment);

    Optional<Payment> findById(String paymentId);
}
