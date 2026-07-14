# 결제 본체(payment) 구현 로드맵

> 설계 `payment-design-v0_2.md` 기준 1차 스코프(결제 본체)의 구현 진행 상황과 남은 작업.
> 작업 방식: 각 항목마다 **브랜치 → TDD(Red→Green→Refactor) → PR** (CLAUDE.md 원칙).

## ✅ 완료

### 부트스트랩 (PR #1 — 머지됨)
- Gradle 모노레포 스캐폴드 (`01_src`: buildSrc 컨벤션, `:payment`, `:event-contracts`)
- Spring Boot 3.4.1 + Java 21, JPA, Flyway, spring-kafka, ShedLock 의존성
- Flyway `V1__init.sql` — paymentDB 전체 테이블(payments, payment_allocation, charges, user_balance/usage/limit, balance_history, payment/charge_idempotency, 실패로그, shedlock)
- `docker-compose.yml` — MySQL 8.4(innodb_lock_wait_timeout=3) / Kafka(KRaft) / Redis
- common 계층 — `Money` 값객체, `ErrorCode`/`BusinessException`/`ErrorResponse`, `GlobalExceptionHandler` + `MoneyTest`
- `CLAUDE.md` — 개발 원칙(TDD, 브랜치/PR)

### balance 컨텍스트 (잔액·한도) (PR #3 — 머지됨)
- **domain** (순수 Java, 단위테스트): `UserBalance`(잔액 음수 방지 불변식), `UserDailyUsage`(자정 lazy 리셋 + 한도 초과 검사), `UserDailyLimit`
- **out 포트**: `BalanceRepository`, `DailyUsageRepository`, `DailyLimitRepository`
- **JPA 어댑터**: 애그리거트별 하위 패키지(balance/usage/limit), 비관적 락 `findByUserIdWithPessimisticLock`(FOR UPDATE), JPA 리포지토리 package-private
- **통합테스트**(Testcontainers MySQL): 3종 라운드트립 + 비관적 락 동시차감 lost-update 방지(8스레드×50회)
- 설계 9장 동시성 보강(락 대기 1s, 커넥션 풀 사이징)

## 🔜 남은 작업 (순서)

### 1. payment 컨텍스트 (결제 본류) — 다음 작업 `feat/payment-core`
- **domain**: `Payment`, `PaymentAllocation`(allocation 합 == total_amount 검증, BALANCE 최대 1개), `PaymentIdempotency`(상태 분기)
- **application**: `ProcessPaymentOrchestrator` — 트랜잭션1(멱등성/잔액/한도 차감) → PG 호출(트랜잭션 밖) → 트랜잭션2(PAID 확정), 보상 트랜잭션(시나리오 22)
- **port**: `ProcessPaymentUseCase`(in), `PgPort`/`PaymentRepository`/`PaymentEventPublisher`(out)
- **adapter**: `PaymentController`(POST /payments), persistence, `MockPgAdapter`, `KafkaEventPublisher`
- **멱등성 분기**: COMPLETED→200(기존응답) / PENDING→409 / FAILED→신규허용 / 없음→신규
- 시나리오 11~24 (분할 결제, 잔액부족, 한도초과, 동시결제, PG 거절/타임아웃, 보상)

### 2. charge 컨텍스트 (충전) `feat/charge`
- `Charge`, `ChargeIdempotency`, `ChargeService` — 트랜잭션2에서 **잔액 증가**(PG 성공 후), **한도 무관**
- `ChargeController`(POST /charges), MockPg·Kafka 재사용

### 3. event-contracts `feat/event-contracts`
- `PaymentCreatedEvent`, `BalanceChargedEvent` 계약 DTO

### 4. 배치 / 크로스커팅 `feat/batch-correction`
- PG 보정 배치(`@Scheduled` 30초, ShedLock) — pg_call_status 조회 → PG 진실 확인 → 트랜잭션2 or 잔액·한도 원복 (설계 6-3)
- kafka_publish_failures 재발행 배치(1분, 설계 10-5)
- compensating_transaction_failures 기록 + 알람 훅

### 5. 통합/E2E + 전체 빌드 검증 `test/e2e`
- 결제·충전 happy path + 멱등성 + 동시성 E2E (Testcontainers MySQL+Kafka)
- `./gradlew build` 그린

## 📌 메모 (재개 시 참고)
- 동시성: **DB 비관적 락 단독**, Redis 미사용 (설계 9장, v0_2 2장 안 B).
- `payment_items` 테이블은 **취소 도메인(2차)** 용 — 본 스코프 결제 흐름은 `payments` + `payment_allocation`만 사용. (v0_2 결제 요청 본문에 item 정보 없음)
- 금액은 원 단위 정수 `Money`.
- ddl-auto: none — 스키마는 Flyway 단독 소유.

## 🧭 2차 스코프 (이후)
취소/환불(`payment-cancel-design_v4_5.md`) + 별도 서비스 모듈: `:risk-management`(Redis 분산락), `:refund-limit`, `:merchant-webhook`.
