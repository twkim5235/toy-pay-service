package com.payment.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 시간 의존 로직(한도 일자 리셋, 멱등성 TTL)이 시스템 시계를 직접 부르지 않도록 Clock을 빈으로 주입한다 —
 * 테스트에서 고정 Clock으로 대체 가능.
 */
@Configuration
class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
