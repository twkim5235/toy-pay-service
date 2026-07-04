package com.payment.payment.adapter.out.persistence.idempotency;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentIdempotencyJpaRepository extends JpaRepository<PaymentIdempotencyJpaEntity, String> {

    Optional<PaymentIdempotencyJpaEntity> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);
}
