package com.payment.payment.adapter.out.persistence.compensation;

import org.springframework.data.jpa.repository.JpaRepository;

interface CompensationFailureJpaRepository extends JpaRepository<CompensationFailureJpaEntity, Long> {
}
