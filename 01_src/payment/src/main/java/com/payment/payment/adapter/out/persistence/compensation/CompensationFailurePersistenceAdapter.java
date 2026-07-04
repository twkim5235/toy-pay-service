package com.payment.payment.adapter.out.persistence.compensation;

import com.payment.common.Money;
import com.payment.payment.application.port.out.CompensationFailureRecorder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class CompensationFailurePersistenceAdapter implements CompensationFailureRecorder {

    private final CompensationFailureJpaRepository jpaRepository;

    @Override
    public void record(String paymentId, FailureType type, Money amount) {
        jpaRepository.save(CompensationFailureJpaEntity.from(paymentId, type, amount));
    }
}
