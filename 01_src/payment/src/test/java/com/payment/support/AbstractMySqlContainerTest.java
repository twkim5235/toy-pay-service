package com.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합테스트 공통 베이스. 운영과 동일한 MySQL 8.4 컨테이너를 띄우고 Flyway가 실제 스키마를 적용한다.
 * {@code innodb-lock-wait-timeout=1}로 설계 9-4 정책을 통합테스트에도 그대로 반영한다.
 */
@Testcontainers
public abstract class AbstractMySqlContainerTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
                    .withDatabaseName("payment")
                    .withUsername("payment")
                    .withPassword("payment")
                    .withCommand(
                            "--innodb-lock-wait-timeout=1",
                            "--character-set-server=utf8mb4",
                            "--collation-server=utf8mb4_unicode_ci");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
