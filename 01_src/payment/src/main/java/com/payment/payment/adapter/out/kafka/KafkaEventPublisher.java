package com.payment.payment.adapter.out.kafka;

import com.payment.payment.application.port.out.PaymentCreatedEvent;
import com.payment.payment.application.port.out.PaymentEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class KafkaEventPublisher implements PaymentEventPublisher {

    private static final String PAYMENT_CREATED = "payment-created";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public void paymentCreated(PaymentCreatedEvent event) {
        kafkaTemplate.send(PAYMENT_CREATED, event.userId(), event);
    }
}
