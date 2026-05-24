plugins {
    id("payment.java-conventions")
    id("org.springframework.boot") version "3.4.1"
    id("io.spring.dependency-management") version "1.1.7"
}

// Spring Boot 3.4.1이 핀한 Testcontainers 1.20.4는 Docker Desktop 4.55(Engine 29 / API 1.52)에서
// docker-java 클라이언트 호출이 400으로 거부됨 → 신버전 docker-java 포함 버전으로 override.
extra["testcontainers.version"] = "1.21.4"

dependencies {
    implementation(project(":event-contracts"))

    // Lombok — adapter/application 레이어의 DI 생성자 보일러플레이트 제거용. 도메인은 순수 Java 유지(미사용).
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    // 웹 / 영속성 / 검증 / 메시징
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    // DB 마이그레이션 (MySQL)
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // 분산 스케줄 락 (다중 인스턴스에서 보정 배치 단일 실행 보장)
    implementation("net.javacrumbs.shedlock:shedlock-spring:5.16.0")
    implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:5.16.0")

    runtimeOnly("com.mysql:mysql-connector-j")

    // 테스트: 단위(JUnit5+AssertJ, 컨벤션) + 슬라이스/통합(Testcontainers MySQL/Kafka)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:kafka")
}
