# payment-platform

간편결제(네이버페이 모델) 시스템. 사용자가 미리 충전한 잔액으로 결제하고, 일일 한도 안에서 사용하며, PG·Kafka가 얽힌 분산 환경에서도 **돈 정합성**을 보장하는 것이 목표다.

설계 문서(`02_doc/design/`) 기반으로 **TDD + 브랜치/PR** 원칙(`CLAUDE.md`)을 지키며 구현한다.

## 스코프

| 차수 | 내용 | 상태 |
|---|---|---|
| **1차 — 결제 본체** | 결제 / 충전 / 잔액·한도 (`payment-design-v0_2`) | 🚧 진행 중 |
| 2차 — 취소·환불 | 취소/환불 + 분산 서비스 모듈 (`payment-cancel-design_v4_5`) | ⏸ 이후 |

## 아키텍처 핵심 결정

- **잔액·한도는 payment 서버 내부 모듈** (별도 서버 X) → 단일 DB 트랜잭션으로 차감 원자성 확보.
- **동시성은 DB 비관적 락 단독** (Redis 분산 락 미사용). 잔액→한도 **고정 순서 락**으로 데드락 회피.
- **PG는 `PgPort` 인터페이스 + Mock 어댑터**, 트랜잭션 **밖**에서 호출. 호출 불확실성(타임아웃)은 보정 배치로 회복.
- **금액은 원 단위 정수 `Money` 값객체** — 음수·음수차감 불변식으로 잔액 음수 방지.
- **멱등성은 `Idempotency-Key` 헤더** + payment/charge 분리 테이블.

## 기술 스택

- Java 21, Spring Boot 3.4.1 (web / data-jpa / validation)
- MySQL 8.4 (Flyway 스키마 단독 소유, `ddl-auto: none`)
- Apache Kafka 3.8 (KRaft), Redis 7.4 (2차 스코프용 선반영)
- ShedLock (배치 중복 방지), Testcontainers (통합테스트), JUnit5 + AssertJ
- Gradle 모노레포 (`buildSrc` 공통 컨벤션)

## 구조

Gradle 모노레포(서비스 = 모듈), 모듈 내부는 **헥사고날 + DDD**.

```
payment_project/
├─ 01_src/                  # 소스 (Gradle wrapper 위치)
│  ├─ payment/              # :payment — 결제 서버 (결제/충전/잔액·한도, 배포단위 1개)
│  │  └─ com.payment.{payment,charge,balance,common}
│  │       ├─ domain/        # 순수 Java. 비즈니스 규칙·값객체 (Spring/JPA 없음)
│  │       ├─ application/   # 유스케이스(트랜잭션 경계) + port/in, port/out
│  │       └─ adapter/       # in/web, out/persistence, out/pg, out/kafka
│  ├─ event-contracts/      # :event-contracts — 서비스간 Kafka 이벤트 DTO (Spring/JPA 의존 X)
│  ├─ buildSrc/             # payment.java-conventions (Java 21, JUnit5, AssertJ)
│  └─ docker-compose.yml    # MySQL / Kafka / Redis
└─ 02_doc/
   ├─ design/               # 설계 문서 (payment-design-v0_2, payment-cancel-design_v4_5)
   └─ planning/             # implementation-roadmap.md
```

**의존 규칙: adapter → application → domain. 도메인은 인프라(Spring/DB/Kafka)를 모른다.**

## 빌드 / 실행 / 테스트

Gradle wrapper는 `01_src/`에 있다.

```bash
cd 01_src
docker compose up -d                                            # 인프라(MySQL/Kafka/Redis)
./gradlew build                                                 # 전체 빌드 + 테스트
./gradlew :payment:test                                         # payment 모듈 테스트
./gradlew :payment:test --tests "com.payment.common.MoneyTest"  # 단일 테스트 클래스
./gradlew :payment:bootRun                                      # 앱 실행
```

## 진행 상황

- ✅ Gradle 모노레포 스캐폴드, Spring Boot + JPA/Flyway/Kafka/ShedLock 의존성
- ✅ Flyway `V1__init.sql` — paymentDB 전체 스키마
- ✅ `docker-compose` (MySQL 8.4 / Kafka KRaft / Redis)
- ✅ common 계층 — `Money` 값객체, 에러 처리(`ErrorCode`/`BusinessException`/`ErrorResponse`/`GlobalExceptionHandler`)
- 🔜 balance(잔액·한도) → payment(결제) → charge(충전) → event-contracts → 배치 → E2E

> 세부 진행 현황과 남은 작업 순서는 [`02_doc/planning/implementation-roadmap.md`](02_doc/planning/implementation-roadmap.md) 참고.

## 개발 원칙

- **TDD** — 도메인/애플리케이션 로직은 실패 테스트(Red) → 구현(Green) → 리팩터 순서. (설정/스키마는 예외)
- **브랜치 분리 + PR** — main 직접 커밋 금지. 작업 단위마다 브랜치 → TDD 커밋 → PR.

자세한 규칙은 [`CLAUDE.md`](CLAUDE.md) 참고.
