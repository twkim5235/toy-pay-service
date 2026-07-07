package com.payment.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 통합테스트 공통 베이스. 운영과 동일한 MySQL 8.4 컨테이너를 띄우고 Flyway가 실제 스키마를 적용한다.
 * {@code innodb-lock-wait-timeout=1}로 설계 9-4 정책을 통합테스트에도 그대로 반영한다.
 *
 * <p>싱글턴 컨테이너 패턴 — {@code @Container}로 JUnit에 수명을 맡기면 클래스 종료 시 컨테이너가 정지되는데,
 * Spring 컨텍스트는 클래스를 넘어 캐시되므로 다음 테스트 클래스가 죽은 포트를 참조한다. static 초기화로
 * 직접 시작해 JVM 전체 동안 유지한다(종료 정리는 Ryuk).
 */
public abstract class AbstractMySqlContainerTest {

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

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
}
