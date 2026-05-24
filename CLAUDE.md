# CLAUDE.md

간편결제(네이버페이 모델) 시스템. 설계 문서 `02_doc/design/`(payment-design-v0_2 = 결제 본체, payment-cancel-design_v4_5 = 취소/환불), 진행 로드맵 `02_doc/planning/implementation-roadmap.md` 기반 구현. 소스는 `01_src/`.

## 개발 원칙 (필수 준수)

### 1. TDD — 테스트 우선
- 모든 도메인/애플리케이션 로직은 **실패하는 테스트(Red) → 구현(Green) → 리팩터(Refactor)** 순서로 개발한다.
- 테스트 없이 프로덕션 코드를 먼저 작성하지 않는다.
- 순수 도메인 = 단위테스트, 애플리케이션 서비스 = out 포트 목(mock), 어댑터 = Testcontainers(MySQL/Kafka) 통합테스트.
- 순수 설정(빌드 스크립트, docker-compose, Flyway 스키마)은 TDD 예외.

### 2. 브랜치 분리 + PR
- **main에 직접 커밋하지 않는다.** 모든 작업은 별도 브랜치에서 한다.
- 브랜치 네이밍: `feat/<context>-<desc>`, `fix/<desc>`, `chore/<desc>`, `test/<desc>`, `refactor/<desc>`.
- 작업 단위(컨텍스트/기능)마다: 브랜치 → TDD 커밋 → `origin` 푸시 → **GitHub PR** 생성.
- PR은 테스트가 그린인 상태에서 올린다. 커밋은 의미 단위로 작게(Red→Green 한 사이클이 커밋 후보).

## 빌드 / 실행 / 테스트

Gradle wrapper는 `01_src/`에 있다.

```bash
cd 01_src
./gradlew build                                                 # 전체 빌드 + 테스트
./gradlew :payment:test                                         # payment 모듈 테스트
./gradlew :payment:test --tests "com.payment.common.MoneyTest"  # 단일 테스트 클래스
./gradlew :payment:bootRun                                      # 앱 실행
docker compose up -d                                            # 인프라(MySQL/Kafka/Redis)
```

## 구조

Gradle 모노레포(서비스 = 모듈), 모듈 내부는 헥사고날 + DDD.

- `:payment` — 결제 서버 (결제/충전/잔액·한도, 배포단위 1개)
- `:event-contracts` — 서비스간 Kafka 이벤트 계약 DTO (Spring/JPA 의존 X)
- `buildSrc` — 공통 빌드 컨벤션(`payment.java-conventions`: Java 21, JUnit5, AssertJ)

`payment` 모듈 패키지(`com.payment.*`)는 바운디드 컨텍스트별로 나뉜다(`payment` / `charge` / `balance` / `common`).
각 컨텍스트 내부:
- `domain/` — 순수 Java. 비즈니스 규칙·값객체. Spring/JPA 애너테이션 없음.
- `application/` — 유스케이스(트랜잭션 경계) + `port/in`, `port/out` 인터페이스.
- `adapter/` — `in/web`(컨트롤러), `out/persistence`(JPA), `out/pg`, `out/kafka`.

**의존 규칙: adapter → application → domain. 도메인은 인프라(Spring/DB/Kafka)를 모른다.**

**Lombok 사용 범위: adapter / application 레이어의 DI 보일러플레이트(`@RequiredArgsConstructor` 등)에만. `domain`은 순수 Java 유지 — Lombok도 쓰지 않는다.**

## 아키텍처 핵심 결정 (설계 근거)
- 잔액·한도는 payment 서버 **내부 모듈**(별도 서버 X) → 단일 DB 트랜잭션 원자성 (v0_2 2장).
- 사용자 동시성은 **DB 비관적 락 단독**(Redis 분산 락 없음). 잔액→한도 **고정 순서 락**으로 데드락 회피 (v0_2 9장).
- PG는 `PgPort` 인터페이스 + Mock 어댑터. **트랜잭션 밖**에서 호출. 호출 불확실성은 보정 배치로 회복 (v0_2 6-3).
- 금액은 원 단위 정수 `Money` 값객체. 음수/음수차감 불변식으로 잔액 음수 방지.
- 멱등성은 `Idempotency-Key` 헤더 + payment/charge 분리 테이블 (v0_2 7장).
