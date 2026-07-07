package com.payment.payment.adapter.out.persistence.payment;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, String> {
}
