---
name: run-itest
description: 이 프로젝트의 Testcontainers MySQL 통합테스트를 실행할 때 사용. Docker 데몬 연결/소켓/버전 이슈를 점검·해결한다. "통합테스트 돌려", "Testcontainers 실행", 또는 Docker 연결 에러(DockerClientProviderStrategy, Could not find a valid Docker environment 등) 발생 시.
---

# Testcontainers 통합테스트 실행

통합테스트는 `com.payment.support.AbstractMySqlContainerTest`를 상속 — 실제 MySQL 8.4 컨테이너 + Flyway 스키마로 검증한다 (`innodb-lock-wait-timeout=1`로 설계 9-4 반영).

## 실행
```bash
cd 01_src
./gradlew :payment:test --tests "com.payment.*IntegrationTest"   # 통합테스트만
./gradlew :payment:test                                          # 도메인+통합 전체
```

## 사전 조건
- **Docker 실행 중**: `docker info` 로 확인. 꺼져 있으면 `open -a Docker` 후 기동 대기.
- **Testcontainers ≥ 1.21.4** (`payment/build.gradle.kts`의 `extra["testcontainers.version"]`). Spring Boot 3.4.1 기본값 1.20.4는 **Docker Desktop 4.55 / Engine 29 / API 1.52에서 docker-java 호출이 400으로 거부**됨 → 이 override를 유지할 것.

## Docker 연결 트러블슈팅 (macOS / Docker Desktop)
증상: `Could not find a valid Docker environment` / `DockerClientProviderStrategy.java`.
원인: `docker`/`curl`로는 데몬이 200을 주는데 테스트 JVM(docker-java)에만 소켓이 안 잡히거나 400.

1. **표준 소켓 활성화 (권장·근본 해결)**: Docker Desktop → Settings → Advanced → **"Allow the default Docker socket to be used"** 체크 → Apply & Restart. `/var/run/docker.sock` 심링크가 생겨 Testcontainers 표준 전략이 사용.
2. 그래도 안 되면 `DOCKER_HOST` 지정: `export DOCKER_HOST="unix://$HOME/.docker/run/docker.sock"`.
   - **주의**: Gradle 데몬이 환경을 캐싱한다 → 이 경우 `--no-daemon`으로 실행하거나, `build.gradle.kts`의 `tasks.test { environment("DOCKER_HOST", ...) }`로 지정.
3. 데몬 연결은 되는데 **HTTP 400**이면 버전 불일치 → Testcontainers 버전을 더 최신으로 올린다(docker-java 호환). 현재 최신 확인: Maven Central `org/testcontainers/testcontainers/maven-metadata.xml`.

## 검증 포인트
- **엔티티 ↔ Flyway 스키마 정합성** (`ddl-auto: validate` 부팅 자체가 검증).
- **비관적 락이 동시 차감 lost update를 막는지** — `ExecutorService`로 N개 동시 차감 후 잔액이 정확히 (초기 − N)인지.
- 각 테스트는 별도 트랜잭션 필요 시 `TransactionTemplate` 사용 (테스트 메서드 `@Transactional` 롤백으로는 동시성 검증 불가).
- 정리(cleanup)는 `JdbcTemplate` truncate로 — package-private JPA 리포지토리에 의존하지 않는다.
