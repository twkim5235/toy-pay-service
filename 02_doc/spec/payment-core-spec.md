# payment-core 구현 스펙

> **이 문서가 무엇인가** — 결제 본체(payment-core)가 **무엇을 하는가**만 기술하는 구현 진실(spec)이다.
> "왜 그렇게 정했나"(trade-off·대안·근거)는 분리된 [payment-core-decisions.md](../design/payment-core-decisions.md)에 ADR(`D-xx`)로 둔다.
>
> **대체 관계** — 본 문서와 decisions 문서가 `02_doc/design/payment-design-v0_2.md`를 대체한다. v0_2는 의사결정 과정을 담은 리뷰 문서로 이력 보존하되, 구현 참조는 본 문서를 따른다.
>
> **단독 완결** — 본 문서는 취소/환불 문서를 읽지 않아도 이해되도록 작성됐다. 취소 문서에 위임됐던 사실(DB 컨벤션, `pg_call_status` 패턴, 실패 추적 테이블 등)은 본문에 인라인했다.
>
> **추적 규칙**
> - `R-xx` = 요구/시나리오. 정의는 **부록 B**에 단독 수록(v0_2 4장 시나리오 번호 계승). 플로우 섹션이 어떤 시나리오를 구현하는지 표시.
> - `D-xx` = 결정. 근거가 필요한 곳에 `(근거: D-xx)`로 decisions 문서를 가리킨다.
> - `INV-x` = 불변식. 2장에 단일 정의를 두고 본문에서 참조한다.
>
> **범위 표기** — `[2차]`는 취소/환불 등 다음 스코프, 본 문서는 인터페이스 경계만 명시한다.

---

## 1. 개요·범위

네이버페이 모델의 **간편결제 본체**. 사용자가 잔액(페이 머니)과 외부 결제수단(카드/계좌)을 보관하고, 가맹점의 결제 요청을 본 시스템이 결제수단을 조합해 처리한다. 충전(외부 수단으로 잔액 채우기)을 함께 다룬다.

### 포함 (in scope)

| 영역 | 내용 |
|---|---|
| 결제 | 단일 수단 / 분할 결제 (R-11~14) |
| 충전 | 외부 결제수단 → 사용자 잔액 (R-25~26) |
| 잔액·한도 | 사용자 잔액 관리, 일일 결제 한도(사기 방지) |
| 멱등성 | `Idempotency-Key` 기반 이중 처리 방지 (R-15, R-27) |
| 동시성 | 같은 사용자 동시 요청 직렬화 (R-18) |
| PG 호출 | 외부 청구 + 호출 불확실성 보정 배치 (R-20~21, R-29) |
| 이벤트 | 결제/충전 이벤트 Kafka 발행 |

### 제외 (out of scope)

- **취소·환불** — `[2차]` 별도 설계. 본 문서는 결제 본류만.
- **PG 회수(환불) 실호출** — 보상 시 이미 청구된 카드의 PG 환불은 **보류**하고 실패 기록만 남긴다. 실제 회수는 `[2차]` 환불 도메인 소관 (근거: D-09).
- 재고(가맹점 책임), 결제수단 등록·인증·토큰화, 가맹점 등록·정산, 사용자 가입·인증, 다중 PG 라우팅.

### 기준

- TPS 100 설계. TPS 1000/10000 확장은 decisions 문서 참조.
- 결제수단 정보는 `method_id`로 외부 참조만. PG는 단일 PG 가정.

---

## 2. 용어·핵심 불변식

### 용어

| 용어 | 뜻 |
|---|---|
| 잔액(balance) | 사용자 페이 머니. 충전으로 증가, 결제로 감소. |
| 일일 한도(daily limit) | 사용자별 일일 결제 누적 상한 (사기 방지). 충전은 무관. |
| allocation | 한 결제를 결제수단별로 나눈 분배. 분할 결제의 단위. |
| 멱등성 키 | 클라이언트가 생성해 헤더로 보내는 UUID. 이중 처리 방지. |
| PG | 외부 결제 게이트웨이. 카드/계좌 청구를 대행. |

### 불변식 (단일 정의 — 본문은 여기를 참조)

- **INV-1 (Money)** — 금액은 원 단위 정수(long). 음수 금지, 음수가 되는 차감 금지. `common/Money` 값객체가 강제 (근거: D-15).
- **INV-2 (잔액 음수 방지)** — 차감 시 `잔액 ≥ 차감액`이 아니면 `INSUFFICIENT_BALANCE`로 거부하고 상태를 바꾸지 않는다 (근거: D-13).
- **INV-3 (한도)** — `누적 + 결제액 > 한도`면 `DAILY_LIMIT_EXCEEDED`. 한도와 같으면 허용. **한도 누적은 결제 전액(`total_amount`)** 기준이며 BALANCE 분배분만이 아니다. 충전은 누적에 영향 없다.
- **INV-4 (분배 합)** — `sum(allocation.amount) == total_amount`. 불일치 시 `INVALID_REQUEST`.
- **INV-5 (BALANCE 유일)** — BALANCE allocation은 최대 1개. BALANCE는 `method_id`를 갖지 않는다.
- **INV-6 (외부 수단 식별)** — 외부 수단(CARD/ACCOUNT)은 `method_id` 필수, 한 결제 안에서 중복 금지.
- **INV-7 (한도 lazy 리셋)** — 한도 누적은 결제·원복 시점에 `마지막 리셋일 < 오늘`이면 0으로 되돌린 뒤 진행한다. 자정 배치는 보조 수단이며 정확성은 이 lazy 리셋이 보장 (근거: D-14).

---

# 참조 (Reference)

## 3. 데이터 모델

**DB 컨벤션** (전체 테이블 공통): FK 제약 미설정(애플리케이션이 정합성 책임), PK는 `VARCHAR(64)` UUID(감사 로그는 `BIGINT AUTO_INCREMENT`), 금액은 `BIGINT` 원 단위(INV-1), 감사 로그는 INSERT-only. 실제 스키마는 `01_src/payment/src/main/resources/db/migration/V1__init.sql`이 단일 소유(`ddl-auto: none`).

### 3-1. 결제

| 테이블 | 목적 | 핵심 컬럼 | 상태 컬럼 |
|---|---|---|---|
| `payments` | 결제 1건 | `id`, `user_id`, `order_id`, `total_amount`, `paid_at` | `status` → 4장 |
| `payment_allocation` | 결제수단별 분배 (1:N) | `payment_id`, `method_type`(BALANCE/CARD/ACCOUNT), `method_id`(BALANCE는 NULL), `amount`, `pg_transaction_id` | `status` → 4장 |

- `payments.user_id` — 결제·잔액·한도를 잇는 핵심 키 (근거: D-12).
- `payment_allocation.pg_transaction_id` — PG 응답 거래 ID. 청구 성공(settle) 시 보존, `[2차]` 환불 근거. (우리가 보낸 `pg_idempotency_key`와 다름: 보낸 키 vs 받은 ID.)

### 3-2. 충전

| 테이블 | 목적 | 핵심 컬럼 | 상태 컬럼 |
|---|---|---|---|
| `charges` | 충전 1건 | `id`, `user_id`, `method_type`(CARD/ACCOUNT), `method_id`, `amount`, `pg_transaction_id` | `status` → 4장 |

`payments`와 분리한다 — 충전은 가맹점·주문 무관, 한도 무관인 별도 도메인.

### 3-3. 사용자 자원 (잔액·한도)

| 테이블 | 목적 | 핵심 컬럼 |
|---|---|---|
| `user_balance` | 사용자 잔액. row 1개 = 사용자 = 동시성 락 단위. | `user_id`(PK), `balance` |
| `balance_history` | 잔액 변경 감사 로그 (INSERT-only, 법적 보존). | `user_id`, `action`(CHARGE/PAYMENT/ROLLBACK, `REFUND`은 `[2차]`), `amount_change`(부호 포함), `balance_after`, `payment_id`/`charge_id` |
| `user_daily_usage` | 일일 결제 누적. | `user_id`(PK), `used_amount`, `last_reset_date` |
| `user_daily_limit` | 일일 한도 설정값(KYC 등급 등 정책, 본 스코프 밖). | `user_id`(PK), `daily_limit` |

- `balance_history.balance_after` — 변경 후 절대 잔액을 직접 저장(누적 계산 회피).
- `version` 컬럼 없음 — 비관적 락 사용이라 낙관적 락 불필요 (근거: D-03).

### 3-4. 멱등성

| 테이블 | 목적 | 핵심 컬럼 | 상태 컬럼 |
|---|---|---|---|
| `payment_idempotency` | 결제 멱등성 + PG 호출 추적 | `idempotency_key`(PK), `user_id`, `order_id`, `payment_id`(NULL 가능), `pg_idempotency_key`, `retry_count`, `expired_at` | `status`, `pg_call_status` → 4장 |
| `charge_idempotency` | 충전 멱등성 | `idempotency_key`(PK), `user_id`, `charge_id`(NULL 가능), … | `status`, `pg_call_status` → 4장 |

- 두 테이블 모두 `UNIQUE (user_id, idempotency_key)` — 같은 키를 다른 사용자가 보내도 별개 처리(보안) (근거: D-05).
- `payment_id`/`charge_id`가 NULL 가능 — 멱등성 row INSERT 시점에는 아직 생성 전, 같은 트랜잭션 안에서 채운다.
- 멱등 보증 유효기간 `expired_at` = 생성 + 24시간 (애플리케이션이 계산해 주입).

### 3-5. 실패 추적 (보조)

| 테이블 | 목적 | 핵심 컬럼 |
|---|---|---|
| `compensating_transaction_failures` | 보상 트랜잭션 실패 — **수동 복구 채널** | `payment_id`/`charge_id`, `failure_type`(BALANCE_ROLLBACK/USAGE_ROLLBACK/PG_REFUND_CALL/REFUND_LIMIT_ROLLBACK), `amount` |
| `kafka_publish_failures` | Kafka 발행 실패 — **자동 재발행 채널** | `topic`, `message_key`, `payload`(JSON), `retry_count` |
| `shedlock` | 보정 배치 분산 단일 실행 락 | `name`(PK), `lock_until`, `locked_at`, `locked_by` |

- payment-core에서 `PG_REFUND_CALL`은 "PG 회수 보류" 기록 용도다 — 실제 PG 환불 호출은 하지 않고 `[2차]` 환불 도메인이 회수한다 (근거: D-09).

### 3-6. `payment_items` — `[2차]` 취소 도메인용

스키마(`payment_items`)는 보유하되 본 결제 흐름은 사용하지 않는다. 결제 본체는 `payments` + `payment_allocation`만 쓴다.

---

## 4. 상태 정의·전이

### 4-1. `payments.status`

| 상태 | 의미 |
|---|---|
| PENDING | 처리 중 (TX1 ~ TX2 commit 전) |
| PAID | 결제 완료 (TX2 commit) |
| FAILED | 실패 (PG 거절/타임아웃 등, 내부 원복 완료) |
| FAILED_REFUNDED | TX2 실패 후 보상 처리됨 (R-22). FAILED와 분석 패턴 구분 |
| PARTIALLY_CANCELLED / CANCELLED | `[2차]` 취소 도메인 |

```
PENDING ──confirm──▶ PAID
   │
   ├──compensate(FAILED)──────────▶ FAILED              (PG 거절·타임아웃 등)
   └──compensate(FAILED_REFUNDED)─▶ FAILED_REFUNDED      (R-22 TX2 실패 / R-23 부분 실패, 청구 성공분 회수 보류)
```

### 4-2. `payment_allocation.status`

| 상태 | 의미 |
|---|---|
| PENDING | 처리 중 |
| SETTLED | 이 수단 청구 완료 (confirm 시 `settle`) |
| FAILED | 이 수단 실패 |
| REFUNDED | `[2차]` |

`PENDING ─settle─▶ SETTLED` / `PENDING ─fail─▶ FAILED`

### 4-3. 멱등성 `status` + 분기 결정

| status | 결정(`resolve`) | 응답 |
|---|---|---|
| COMPLETED | REPLAY | 200, 기존 결과 본문 |
| PENDING | IN_PROGRESS | 409 `PAYMENT_IN_PROGRESS` |
| FAILED | PROCEED_NEW | 신규 처리 허용 (근거: D-06) |
| (행 없음) | — | 신규 처리 |

`PENDING ─complete─▶ COMPLETED` (pg_call_status도 SUCCESS로) / `PENDING ─fail─▶ FAILED`

### 4-4. `pg_call_status` — PG 호출 추적

| 상태 | 의미 |
|---|---|
| NOT_CALLED | PG 호출 전 (초기값) |
| CALLING | PG 호출 직전 마킹 (별도 commit) |
| SUCCESS | 청구 성공 확인 |
| FAILED | 청구 실패 확인 |
| UNKNOWN | 응답 불확실(타임아웃 등) — 보정 배치 대상 |

`NOT_CALLED ─markPgCalling─▶ CALLING ─markPgStatus─▶ {SUCCESS|FAILED|UNKNOWN}`

**별도 commit인 이유**: PG 호출 중 서버가 죽어 CALLING이 사라지면 보정 배치가 미완료 호출을 인지하지 못한다. 보정 배치는 `pg_call_status IN (CALLING, UNKNOWN)`을 대상으로 잡는다 (근거: D-08).

---

## 5. 동시성 규칙

- **DB 비관적 락 단독.** Redis 분산 락 없음. `SELECT … FOR UPDATE`는 인스턴스 간에도 작동해 다중 인스턴스 직렬화를 보장한다 (근거: D-03).
- **락 단위 = `user_id`.** `user_balance`/`user_daily_usage` 모두 PK가 user_id.
- **두 row 락은 `balance → usage` 고정 순서** (데드락 회피, 근거: D-04). `reserve`와 `compensate`가 같은 순서를 따른다.
- **PG 호출은 트랜잭션 밖.** 락 보유 시간을 수십 ms로 유지(트랜잭션 안에 외부 호출 금지).
- **잔액 음수 방지(INV-2)** — validation 단계 + 락 안 재확인. 락 안에서 `FOR UPDATE` 후 도메인이 차감 가부를 판정.

### 타임아웃 (근거: D-07)

| 항목 | 값 | 의미 |
|---|---|---|
| `innodb_lock_wait_timeout` | **1초** | 락 대기 한도. 초과 시 409 `PAYMENT_IN_PROGRESS` |
| HikariCP `connectionTimeout` | 2~3초 | 풀에서 커넥션 받기까지 대기 (기본 30초 금지) |
| HikariCP `maximumPoolSize` | 10 | TPS 100 기준. 락 경합 흡수용으로 키우지 말 것 |
| 트랜잭션 timeout | 10초 | 전체 트랜잭션 한도 |

---

## 6. API 계약

### 6-1. `POST /payments`

**헤더**

| 헤더 | 필수 | 설명 |
|---|---|---|
| `Idempotency-Key` | Yes | 클라이언트 생성 UUID v4. 없으면 400 `MISSING_IDEMPOTENCY_KEY` |
| `Authorization` | Yes | 사용자 토큰 또는 가맹점 API 키 |

**요청 본문**

```json
{
  "user_id": "USR_001",
  "order_id": "MERCH_ORD_123",
  "total_amount": 1000000,
  "allocations": [
    {"method_type": "BALANCE", "amount": 300000},
    {"method_type": "CARD", "method_id": "CARD_abc", "amount": 700000}
  ]
}
```

**서버 검증** — INV-4(합=total), INV-5(BALANCE≤1), INV-6(method_id 중복 금지) 위반 시 400 `INVALID_REQUEST`.

**응답 본문 (성공 200)**

```json
{
  "payment_id": "PAY_001",
  "status": "PAID",
  "user_id": "USR_001",
  "order_id": "MERCH_ORD_123",
  "total_amount": 1000000,
  "paid_at": "2024-11-15T10:23:45Z",
  "allocations": [
    {"method_type": "BALANCE", "amount": 300000, "status": "SETTLED"},
    {"method_type": "CARD", "method_id": "CARD_abc", "amount": 700000, "status": "SETTLED", "pg_transaction_id": "PG_xyz"}
  ]
}
```

**상태 코드** → 8장 에러 표.

### 6-2. `POST /charges`

헤더는 `/payments`와 동일.

**요청 본문**

```json
{ "user_id": "USR_001", "method_type": "CARD", "method_id": "CARD_abc", "amount": 500000 }
```

**응답 본문 (성공 200)**

```json
{ "charge_id": "CHG_001", "status": "COMPLETED", "user_id": "USR_001", "amount": 500000, "balance_after": 800000, "charged_at": "2024-11-15T10:25:30Z" }
```

충전엔 잔액 부족·한도 초과가 없다. PG 거절/불확실/멱등성은 결제와 동일.

> 취소 API(`POST /payments/{id}/cancel`)는 `[2차]`.

---

## 7. 이벤트 계약 (Kafka)

| 토픽 | 발행 시점 | 파티션 키 | 파티션 | 컨슈머 |
|---|---|---|---|---|
| `payment-created` | 결제 PAID 확정 후 | `user_id` | 12 | merchantWebhook / settlement / rewards |
| `balance-charged` | 충전 COMPLETED 후 | `user_id` | 6 | rewards |

- 파티션 키 `user_id` — 같은 사용자 이벤트 시퀀스 순서 보장 (근거: D-11).
- 프로듀서: `acks=all`, `enable.idempotence=true`, manual commit(at-least-once).
- **발행 실패 처리**: 사용자에겐 이미 200. 발행 실패는 `kafka_publish_failures`에 적재하고 재발행 배치(1분 주기)가 가맹점에 전파한다 (결과적 일관성, R-24).

---

## 8. 에러 코드 표

`common/error/ErrorCode`가 HTTP 상태를 보관(도메인은 Spring 비의존).

| 코드 | HTTP | 발생 시나리오 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 금액 불일치, method 규칙 위반, 한도 설정 없음 등 |
| `MISSING_IDEMPOTENCY_KEY` | 400 | `Idempotency-Key` 헤더 없음 |
| `INSUFFICIENT_BALANCE` | 400 | 잔액 부족 (R-16) |
| `DAILY_LIMIT_EXCEEDED` | 400 | 일일 한도 초과 (R-17) |
| `PG_DECLINED` | 400 | PG 청구 거절 (R-19, R-23) |
| `PAYMENT_IN_PROGRESS` | 409 | 같은 결제 멱등성 키 처리 중 (R-15) / 락 대기 초과 |
| `CHARGE_IN_PROGRESS` | 409 | 같은 충전 멱등성 키 처리 중 (R-27) |
| `INTERNAL_ERROR` | 500 | 일반 내부 오류 |
| `INCONSISTENT_STATE` | 500 | 정합성 깨짐 — 보정 배치가 회복 (R-20), TX2 실패 보상 (R-22) |
| `PG_UNAVAILABLE` | 503 | PG 서비스 장애 |

---

# 플로우 (Behavior)

> 플로우는 트랜잭션 경계 협력자 `PaymentTransactionService`(`reserve`/`markPgCalling`/`markPgStatus`/`confirm`/`compensate`)를 오케스트레이터 `ProcessPaymentOrchestrator`가 PG 호출을 사이에 끼고 호출하는 구조다. **오케스트레이터는 `@Transactional`을 걸지 않는다** — PG 호출이 트랜잭션 밖이어야 하므로 (근거: D-08, 5장).

## 9. 결제 처리 — Happy Path (R-11~14)

```
[TX1 reserve]  락(balance→usage) → 한도 누적(INV-3) → 잔액 차감(INV-2, BALANCE 분배분)
               → balance_history append → 멱등성 PENDING + payments PENDING + allocation INSERT → COMMIT
       │  (락 해제)
[별도]  markPgCalling : pg_call_status = CALLING (commit)
[밖]    PgPort.charge(외부 수단별)  →  결과 APPROVED / DECLINED / TIMEOUT
[별도]  markPgStatus : pg_call_status = SUCCESS (commit)
       │
[TX2 confirm]  외부 allocation settle(pg_transaction_id 보존) → payments PAID(paid_at)
               → 멱등성 COMPLETED → COMMIT
       │
[밖]    payment-created 발행 → 200 OK
```

- 한도는 `total_amount` 전액 누적, 잔액 차감은 `BALANCE` allocation 금액만(없으면 0) (INV-3).
- 신규 사용자는 잔액/누적을 0으로 보정 후 진행. 한도 설정(`user_daily_limit`)이 없으면 `INVALID_REQUEST`.
- 단일 수단(잔액 단독/카드 단독)은 allocation 1개인 분할 결제의 특수형 — 같은 경로.

## 10. 결제 예외·보상

| 시나리오 | 처리 | 응답 |
|---|---|---|
| **R-16 잔액 부족** | `reserve` 중 `UserBalance.deduct`가 INV-2로 거부, TX1 롤백 | 400 `INSUFFICIENT_BALANCE` |
| **R-17 한도 초과** | `reserve` 중 `UserDailyUsage.use`가 INV-3로 거부, TX1 롤백 | 400 `DAILY_LIMIT_EXCEEDED` |
| **R-18 동시 결제** | `balance` row `FOR UPDATE` 락으로 직렬화. 뒤 요청은 앞 commit 후 잔액 재확인 | 정상 또는 400 |
| **R-19 PG 거절(DECLINED)** | `markPgStatus(FAILED)` → `compensate(FAILED)`: 잔액·한도 원복(balance→usage 고정 순서), allocation FAILED, payments FAILED, 멱등성 FAILED | 400 `PG_DECLINED` |
| **R-20 PG 타임아웃(TIMEOUT)** | `markPgStatus(UNKNOWN)`. **내부 원복 안 함.** 보정 배치가 PG 진실 조회 후 회복(13장) | 500 `INCONSISTENT_STATE` (재요청으로 최종 결과 확인) |
| **R-22 TX2 실패** | `compensate(FAILED_REFUNDED)`: 내부 잔액·한도 원복 + **PG 회수 보류**(이미 청구된 카드는 `compensating_transaction_failures`에 `PG_REFUND_CALL` 기록, 실호출 안 함 — 근거 D-09). payments FAILED_REFUNDED | 500 `INCONSISTENT_STATE` |
| **R-23 복수 수단 부분 실패** | R-22와 동일 원칙: 내부 원복 + 성공분 PG 회수 보류 기록. 청구 성공분이 있어 **payments `FAILED_REFUNDED`**(회수 의무 보유), 거절된 allocation은 `FAILED`·성공분 allocation은 회수 대기로 `SETTLED` 유지 | 400 `PG_DECLINED` |

- R-20과 R-22의 차이: **타임아웃은 원복하지 않고 보정에 위임**(이중 청구 위험), **TX2 실패는 즉시 보상 시도**.
- 보상의 잔액·한도 원복은 단일 DB 트랜잭션이라 실패 위험이 낮다. 원복 자체가 실패하면 `BALANCE_ROLLBACK`/`USAGE_ROLLBACK`으로 기록 + 알람.

## 11. 충전 처리 (R-25~30)

```
[TX1]  charge_idempotency PENDING + charges PENDING INSERT → COMMIT   (잔액 변화 없음)
[별도] pg_call_status = CALLING
[밖]   PgPort.charge → APPROVED / DECLINED / TIMEOUT
[별도] pg_call_status = SUCCESS
[TX2]  락(balance) → 잔액 증가(+) → balance_history(CHARGE) → charges COMPLETED + 멱등성 COMPLETED → COMMIT
[밖]   balance-charged 발행 → 200 OK
```

- **결제와의 핵심 차이**: 잔액 증가가 TX2 안(PG 성공 후). 한도 무관 (근거: D-10, INV-3).
- **R-28 PG 거절**: 잔액 미증가 상태라 보상 불필요. `charges FAILED` 마킹. 400 `PG_DECLINED`.
- **R-29 PG 타임아웃**: 보정 배치 위임. 성공 확인 시 잔액 증가 + COMPLETED, 실패 확인 시 FAILED(보상 없음).
- **R-30 충전 후 즉시 결제**: 같은 사용자 락으로 자연 직렬화. 충전 commit 전 결제가 락을 잡으면 충전 전 잔액 기준 처리 — 양쪽 모두 안전.

## 12. 멱등성 분기 (R-15, R-27)

요청 진입 시 `(user_id, idempotency_key)`로 멱등성 행을 조회해 4-3 표대로 분기한다. 결제·충전 각각 자기 멱등성 테이블을 본다.

- 행 없음 → 신규 처리(9·11장).
- COMPLETED → 200 + 기존 결과 재구성(REPLAY).
- PENDING → 409 (`PAYMENT_IN_PROGRESS` / `CHARGE_IN_PROGRESS`).
- FAILED → 신규 처리 허용 (결제 실패 원인은 시간이 지나면 해소 가능, 근거 D-06).

## 13. PG 보정 배치 (R-20·R-21·R-29)

```
@Scheduled(30초), ShedLock(분산 단일 실행)
대상: pg_call_status IN ('CALLING','UNKNOWN') AND created_at < now() - 1분
각 대상 → PG 진실 조회 (pg_idempotency_key)
  ├ 성공 확인  → [결제] TX2 재수행(PAID/COMPLETED) + payment-created 발행
  │              [충전] 잔액 증가 + COMPLETED + balance-charged 발행
  ├ 실패 확인  → [결제] 잔액·한도 원복 + payments FAILED + 멱등성 FAILED
  │              [충전] charges FAILED (보상 없음)
  ├ 처리 중    → 스킵 (다음 주기 재조회)
  └ 조회 실패  → retry_count++ ; MAX_RETRY 초과 시 알람
```

- 주기 30초 / 유예 1분 — 결제 불확실은 사용자 체감 손해가 커 빠른 회복 우선 (근거: D-08).

---

## 부록 A. 미해결 / 추후 결정

본 스코프에서 명시적으로 보류한 사항.

1. **PG 회수(환불) 실호출** — `[2차]` 환불 도메인. payment-core는 보류 기록만 (D-09).
2. **자동 충전** (잔액 부족 시 카드에서 자동 충전 후 결제).
3. **KYC 등급별 한도** 매핑 — 본 문서는 한도값 자체만.
4. **잔액 출금** (페이 머니 → 외부 계좌).
5. **결제수단 도메인 통합** — 현재는 `method_id` 외부 참조만.
6. **잔액 row 확장** — TPS 10000+ 시 샤딩/CQRS (decisions 문서 확장 전략).

---

## 부록 B. 요구·시나리오 정의 (R-xx)

본 문서가 참조하는 `R-xx`의 단일 정의. v0_2 4장 시나리오 번호를 계승하되, **메커니즘(어떻게)은 본문 플로우·decisions(D-xx)가 진실**이고 여기서는 **시나리오(무엇)**만 기술한다. 본문이 `R-xx`로 가리키는 동작이 v0_2와 의도적으로 다른 경우 "비고"에 명시한다.

### B-1. 결제 — 정상 플로우 (구현: 9장)

| R | 시나리오 | 기대 결과 |
|---|---|---|
| R-11 | 잔액 단독 결제 | 잔액 차감 + 한도 누적(전액) → payments `PAID` |
| R-12 | 카드(외부 수단) 단독 결제 | 잔액 변화 없음 + 한도 누적 + PG 청구 → `PAID` |
| R-13 | 분할 결제 (잔액 + 카드) — **메인 시나리오** | 잔액 차감 + PG 청구 + 한도 누적(전액) → `PAID` |
| R-14 | 3개 수단 분할 결제 (잔액 + 카드 A + 카드 B) | 외부 수단별 PG 청구를 한 결제로 처리 → `PAID` |

### B-2. 결제 — 엣지 케이스 (구현: 10·12·13장)

| R | 시나리오 | 기대 결과 |
|---|---|---|
| R-15 | 동일 요청 중복 결제 (같은 `Idempotency-Key` 재전송) | 멱등성 분기(4-3): COMPLETED→REPLAY 200 / PENDING→409 / FAILED→신규 |
| R-16 | 사용자 잔액 부족 | 400 `INSUFFICIENT_BALANCE` (validation + 락 안 재확인, INV-2) |
| R-17 | 일일 한도 초과 | 400 `DAILY_LIMIT_EXCEEDED` (INV-3) |
| R-18 | 같은 사용자 동시 결제 | `user_balance` 락으로 직렬화 → 정상 또는 400 |
| R-19 | PG 명시적 거절(DECLINED) | 내부 잔액·한도 원복 → 400 `PG_DECLINED` |
| R-20 | PG 호출 불확실(TIMEOUT) | 내부 원복 안 함, 보정 배치 위임 → 500 `INCONSISTENT_STATE` |
| R-21 | 보정 배치 후 처리 분기 | PG 진실 조회: 성공 확인→TX2 재수행(PAID) / 실패 확인→원복+FAILED |
| R-22 | TX2(확정) 실패 — 정합성 깨짐 | 내부 원복 + 청구분 **PG 회수 보류 기록** → `FAILED_REFUNDED`, 500 |
| R-23 | 복수 수단 부분 실패 (일부 청구 성공/일부 거절) | 내부 원복 + 성공분 PG 회수 보류 기록 → `FAILED_REFUNDED`, 400 `PG_DECLINED` |
| R-24 | Kafka 발행 실패 | 사용자엔 200, `kafka_publish_failures` 적재 후 재발행 배치(결과적 일관성) |

> **v0_2 대비 차이** — v0_2 시나리오 22·23은 보상 시 "PG 환불 먼저" 호출이었으나, 본 스코프는 **회수 보류·기록만**으로 대체한다 (근거: D-09). R-18의 "분산 락"도 DB 비관적 락 단독으로 대체 (근거: D-03).

### B-3. 충전 — 정상 플로우 (구현: 11장)

| R | 시나리오 | 기대 결과 |
|---|---|---|
| R-25 | 단일 수단 충전 (카드로 잔액 충전) | PG 청구 성공 후 잔액 증가 → charges `COMPLETED` |
| R-26 | 충전 후 즉시 결제 | 충전 commit 후 잔액 반영 → 다음 결제에서 사용 가능 (R-30과 연결) |

### B-4. 충전 — 엣지 케이스 (구현: 11·12·13장)

| R | 시나리오 | 기대 결과 |
|---|---|---|
| R-27 | 충전 요청 중복 (같은 `Idempotency-Key`) | 충전 멱등성 분기(4-3) → 중복 시 409 `CHARGE_IN_PROGRESS` 등 |
| R-28 | 충전 PG 거절 | 잔액 미증가 상태라 보상 불필요, `charges FAILED` → 400 `PG_DECLINED` |
| R-29 | 충전 PG 불확실(TIMEOUT) | 보정 배치 위임: 성공 확인→잔액 증가+COMPLETED / 실패 확인→FAILED(보상 없음) |
| R-30 | 충전 후 즉시 결제 동시성 | 같은 사용자 락으로 자연 직렬화 — 양쪽 모두 정합성 안전 |