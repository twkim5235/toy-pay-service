package com.payment.payment.adapter.out.kafka;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.payment.payment.application.port.out.PaymentCreatedEvent;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class KafkaEventPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);

    private final KafkaEventPublisher publisher = new KafkaEventPublisher(kafkaTemplate);

    @Test
    @DisplayName("payment-created 토픽에 userId를 파티션 키로 이벤트를 발행한다")
    void publishesPaymentCreatedWithUserIdKey() {
        PaymentCreatedEvent event = new PaymentCreatedEvent(
                "PAY1", "u1", "ord1", 1_000_000L, Instant.parse("2026-07-14T00:00:00Z"));

        publisher.paymentCreated(event);

        // 파티션 키 = userId: 같은 사용자의 이벤트가 같은 파티션에서 순서 보장된다 (결정 D-11).
        verify(kafkaTemplate).send("payment-created", "u1", event);
    }
}
