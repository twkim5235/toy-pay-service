package com.payment.payment.adapter.out.persistence.idempotency;

import com.payment.payment.application.port.out.PaymentIdempotencyRepository;
import com.payment.payment.domain.PaymentIdempotency;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PaymentIdempotencyPersistenceAdapter implements PaymentIdempotencyRepository {

    private final PaymentIdempotencyJpaRepository jpaRepository;

    @Override
    public Optional<PaymentIdempotency> find(String userId, String idempotencyKey) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                .map(PaymentIdempotencyJpaEntity::toDomain);
    }

    @Override
    public void save(PaymentIdempotency idempotency) {
        jpaRepository.save(PaymentIdempotencyJpaEntity.from(idempotency));
    }
}
