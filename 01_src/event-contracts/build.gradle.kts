plugins {
    id("payment.java-conventions")
}

// 서비스간 Kafka 이벤트 계약(DTO)만 담는 얇은 공유 모듈.
// Spring/JPA 등 인프라 의존성을 두지 않는다.
dependencies {
    // jackson 직렬화 애너테이션만 (가벼운 공유)
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.18.2")
}
