package com.payment.payment.adapter.out.persistence.payment;

import com.payment.payment.application.port.out.PaymentRepository;
import com.payment.payment.domain.Payment;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class PaymentPersistenceAdapter implements PaymentRepository {

    private final PaymentJpaRepository jpaRepository;

    @Override
    public void save(Payment payment) {
        jpaRepository.save(PaymentJpaEntity.from(payment));
    }

    @Override
    public Optional<Payment> findById(String paymentId) {
        return jpaRepository.findById(paymentId).map(PaymentJpaEntity::toDomain);
    }
}
