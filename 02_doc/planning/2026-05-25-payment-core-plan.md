# payment 컨텍스트(결제 본류) 구현 계획

> **For agentic workers:** 이 계획은 작업 단위로 실행한다. 각 단위는 프로젝트 스킬 `tdd-cycle`(Red→Green→Refactor)로 진행하고, 골격 스캐폴드는 `new-context`, 통합테스트 실행은 `run-itest`, 변경 리뷰는 `kent-beck-review`를 쓴다. 스텝은 체크박스(`- [ ]`)로 추적한다.

**Goal:** 분할 결제(잔액+카드)를 멱등하게 처리하는 `POST /payments`를 헥사고날+DDD로 구현한다. 설계 `payment-design-v0_2.md` 4-1·6-1·6-3·7·8·9장 기준.

**Architecture:** orchestrator(`ProcessPaymentService`)가 트랜잭션 경계를 가진 협력자(`PaymentTransactionService`, `@Transactional`)와 PG 포트를 조합한다. TX1(잔액·한도 차감) → PG 호출(트랜잭션 밖) → TX2(PAID 확정)의 3단 구조이며, PG 호출이 트랜잭션 밖이라 단일 `@Transactional` 메서드로 묶지 않는다. 동시성은 기존 balance 컨텍스트의 비관적 락(`findByUserIdWithPessimisticLock`)을 잔액→한도 고정 순서로 재사용한다.

**Tech Stack:** Java 21, Spring Boot 3.4.1(web/data-jpa/validation/kafka), MySQL 8.4 + Flyway(V1 적용 완료), Testcontainers, JUnit5 + AssertJ, Lombok(adapter/application DI만).

**확정 범위 (2026-05-25 설계 검토):**
- **PG 환불(카드 회수) 보류** — 시나리오 22/23에서 잔액·한도·history **내부 원복만** 수행, 이미 청구된 카드 회수는 `compensating_transaction_failures(failure_type=PG_REFUND_CALL)` 기록 + 알람 훅으로 남기고 2차(환불 도메인)에 위임. **`PgPort`는 `charge`만, `refund` 없음.**
- **balance_history 포함** — 결제 차감(PAYMENT)·보상 원복(ROLLBACK)마다 INSERT.
- Kafka는 `PaymentEventPublisher` 포트 + 최소 payload로 발행. 정식 `:event-contracts` DTO는 로드맵 item 3에서 정리.
- 사전 validation은 도메인 생성 불변식(금액 합·BALANCE≤1·method_id 유일)으로, 잔액·한도 검사는 **락 안에서만**(설계 16/17의 "락 안 재체크가 정확성 보장") — 사전 잔액조회 최적화는 YAGNI로 생략.

---

## 파일 구조

기존 `com.payment.balance.*` 패턴을 그대로 따른다(엔티티 package-private + `from`/`toDomain`, 어댑터 `@Repository @RequiredArgsConstructor` package-private이 public 포트 구현, 도메인은 순수 Java).

```
com.payment.payment
├── domain/
│   ├── Payment.java                  # 애그리거트: 불변식 + 상태전이
│   ├── PaymentAllocation.java        # 결제수단별 분배 + 상태전이
│   ├── PaymentMethodType.java        # enum BALANCE/CARD/ACCOUNT
│   ├── PaymentStatus.java            # enum PENDING/PAID/FAILED/FAILED_REFUNDED
│   ├── AllocationStatus.java         # enum PENDING/SETTLED/FAILED/REFUNDED
│   ├── PaymentIdempotency.java       # 멱등 분기 + pg_call_status 전이
│   ├── IdempotencyStatus.java        # enum PENDING/COMPLETED/FAILED
│   └── PgCallStatus.java             # enum NOT_CALLED/CALLING/SUCCESS/FAILED/UNKNOWN
├── application/
│   ├── ProcessPaymentService.java        # orchestrator (use case 구현, 트랜잭션 X)
│   ├── PaymentTransactionService.java     # @Transactional 협력자 (TX1/PG상태/TX2/보상)
│   └── port/
│       ├── in/  ProcessPaymentUseCase.java, ProcessPaymentCommand.java, PaymentResult.java
│       └── out/ PgPort.java(+PgChargeRequest/PgChargeResult), PaymentRepository.java,
│               PaymentIdempotencyRepository.java, CompensationFailureRecorder.java,
│               PaymentEventPublisher.java(+PaymentCreatedEvent)
├── adapter/
│   ├── in/web/  PaymentController.java, PaymentRequest.java, PaymentResponse.java
│   └── out/
│       ├── persistence/payment/      PaymentJpaEntity, PaymentAllocationJpaEntity, *JpaRepository, PaymentPersistenceAdapter
│       ├── persistence/idempotency/  PaymentIdempotencyJpaEntity, *JpaRepository, IdempotencyPersistenceAdapter
│       └── persistence/compensation/ CompensatingFailureJpaEntity, *JpaRepository, CompensationFailureAdapter
│       ├── pg/                        MockPgAdapter
│       └── kafka/                     KafkaEventPublisher
com.payment.balance        # balance_history 추가 (잔액 변경 감사라 balance 컨텍스트 소유)
├── domain/BalanceHistory.java, BalanceAction.java(enum)
├── application/port/out/BalanceHistoryRepository.java
└── adapter/out/persistence/history/  BalanceHistoryJpaEntity, *JpaRepository, BalanceHistoryPersistenceAdapter
```

**크로스 컨텍스트 의존(허용):** payment 애플리케이션은 오케스트레이터라서 balance 애플리케이션 포트(`BalanceRepository`/`DailyUsageRepository`/`DailyLimitRepository`/`BalanceHistoryRepository`)와 balance 도메인을 사용한다. 의존 방향은 adapter→application→domain 유지.

---

## Phase A — 골격 + 도메인 (순수 Java, 단위테스트)

### Task 1: payment 컨텍스트 골격 + enum

**Files:**
- Create: `payment/src/main/java/com/payment/payment/domain/PaymentMethodType.java`
- Create: `.../domain/PaymentStatus.java`, `.../domain/AllocationStatus.java`, `.../domain/IdempotencyStatus.java`, `.../domain/PgCallStatus.java`
- (패키지 골격은 `new-context` 스킬로 스캐폴드)

- [ ] **Step 1: `new-context` 스킬로 payment 컨텍스트 골격 스캐폴드** (domain/application/adapter 패키지 + 의존규칙 확인)

- [ ] **Step 2: enum 5종 작성** (테스트 불필요 — 순수 상수)

```java
package com.payment.payment.domain;
public enum PaymentMethodType { BALANCE, CARD, ACCOUNT;
    public boolean isExternal() { return this != BALANCE; } }
```
```java
public enum PaymentStatus { PENDING, PAID, FAILED, FAILED_REFUNDED }
public enum AllocationStatus { PENDING, SETTLED, FAILED, REFUNDED }
public enum IdempotencyStatus { PENDING, COMPLETED, FAILED }
public enum PgCallStatus { NOT_CALLED, CALLING, SUCCESS, FAILED, UNKNOWN }
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd 01_src && ./gradlew :payment:compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add 01_src/payment/src/main/java/com/payment/payment/domain
git commit -m "feat(payment): scaffold payment context skeleton and enums"
```

---

### Task 2: `PaymentAllocation` 도메인

**Files:**
- Create: `.../domain/PaymentAllocation.java`
- Test: `payment/src/test/java/com/payment/payment/domain/PaymentAllocationTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.*;
import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAllocationTest {

    @Test @DisplayName("BALANCE 수단은 method_id가 없어야 한다")
    void balance_has_no_method_id() {
        PaymentAllocation a = PaymentAllocation.balance(Money.won(30_000));
        assertThat(a.methodType()).isEqualTo(PaymentMethodType.BALANCE);
        assertThat(a.methodId()).isNull();
        assertThat(a.status()).isEqualTo(AllocationStatus.PENDING);
    }

    @Test @DisplayName("외부 수단(CARD)은 method_id가 필수다")
    void external_requires_method_id() {
        assertThatThrownBy(() -> PaymentAllocation.external(PaymentMethodType.CARD, null, Money.won(1000)))
                .isInstanceOf(BusinessException.class);
    }

    @Test @DisplayName("settle 시 SETTLED + pg_transaction_id 보존")
    void settle_records_pg_tx() {
        PaymentAllocation a = PaymentAllocation.external(PaymentMethodType.CARD, "CARD_a", Money.won(1000));
        a.settle("PG_tx_1");
        assertThat(a.status()).isEqualTo(AllocationStatus.SETTLED);
        assertThat(a.pgTransactionId()).isEqualTo("PG_tx_1");
    }

    @Test @DisplayName("fail 시 FAILED")
    void fail_marks_failed() {
        PaymentAllocation a = PaymentAllocation.balance(Money.won(1000));
        a.fail();
        assertThat(a.status()).isEqualTo(AllocationStatus.FAILED);
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `cd 01_src && ./gradlew :payment:test --tests "com.payment.payment.domain.PaymentAllocationTest"` → FAIL (PaymentAllocation 없음)

- [ ] **Step 3: 최소 구현**

```java
package com.payment.payment.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;

public class PaymentAllocation {
    private final String id;
    private final PaymentMethodType methodType;
    private final String methodId;       // BALANCE면 null
    private final Money amount;
    private AllocationStatus status;
    private String pgTransactionId;

    PaymentAllocation(String id, PaymentMethodType methodType, String methodId,
                      Money amount, AllocationStatus status, String pgTransactionId) {
        if (methodType.isExternal() && (methodId == null || methodId.isBlank())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "외부 결제수단은 method_id가 필요합니다");
        }
        if (methodType == PaymentMethodType.BALANCE && methodId != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "BALANCE 수단은 method_id를 가질 수 없습니다");
        }
        if (!amount.isPositive()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분배 금액은 양수여야 합니다");
        }
        this.id = id; this.methodType = methodType; this.methodId = methodId;
        this.amount = amount; this.status = status; this.pgTransactionId = pgTransactionId;
    }

    public static PaymentAllocation balance(Money amount) {
        return new PaymentAllocation(java.util.UUID.randomUUID().toString(),
                PaymentMethodType.BALANCE, null, amount, AllocationStatus.PENDING, null);
    }
    public static PaymentAllocation external(PaymentMethodType type, String methodId, Money amount) {
        return new PaymentAllocation(java.util.UUID.randomUUID().toString(),
                type, methodId, amount, AllocationStatus.PENDING, null);
    }
    /** DB 복원용. */
    public static PaymentAllocation restore(String id, PaymentMethodType type, String methodId,
            Money amount, AllocationStatus status, String pgTransactionId) {
        return new PaymentAllocation(id, type, methodId, amount, status, pgTransactionId);
    }

    public void settle(String pgTransactionId) { this.status = AllocationStatus.SETTLED; this.pgTransactionId = pgTransactionId; }
    public void fail() { this.status = AllocationStatus.FAILED; }
    public boolean isExternal() { return methodType.isExternal(); }

    public String id() { return id; }
    public PaymentMethodType methodType() { return methodType; }
    public String methodId() { return methodId; }
    public Money amount() { return amount; }
    public AllocationStatus status() { return status; }
    public String pgTransactionId() { return pgTransactionId; }
}
```

- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add PaymentAllocation with method invariants (TDD)"`

---

### Task 3: `Payment` 애그리거트 (불변식 + 상태전이)

**Files:**
- Create: `.../domain/Payment.java`
- Test: `.../domain/PaymentTest.java`

불변식(설계 8-1): `Σamount == total_amount`, BALANCE allocation ≤ 1개, 외부 method_id 중복 없음, allocations 비어있지 않음.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.*;
import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentTest {

    private PaymentAllocation balance(long v) { return PaymentAllocation.balance(Money.won(v)); }
    private PaymentAllocation card(String id, long v) { return PaymentAllocation.external(PaymentMethodType.CARD, id, Money.won(v)); }

    @Test @DisplayName("분배 합이 total과 같으면 생성된다(메인: 잔액30만+카드70만)")
    void create_split_payment() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000), List.of(balance(300_000), card("c1", 700_000)));
        assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.allocations()).hasSize(2);
    }

    @Test @DisplayName("분배 합이 total과 다르면 INVALID_REQUEST")
    void sum_mismatch_rejected() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(1_000_000), List.of(balance(300_000), card("c1", 600_000))))
                .isInstanceOf(BusinessException.class);
    }

    @Test @DisplayName("BALANCE allocation은 최대 1개")
    void balance_at_most_one() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(2000), List.of(balance(1000), balance(1000))))
                .isInstanceOf(BusinessException.class);
    }

    @Test @DisplayName("외부 method_id 중복은 거부")
    void duplicate_method_id_rejected() {
        assertThatThrownBy(() -> Payment.create("u1", "ord1", Money.won(2000), List.of(card("c1", 1000), card("c1", 1000))))
                .isInstanceOf(BusinessException.class);
    }

    @Test @DisplayName("balanceAllocation/externalAllocations 분리")
    void split_helpers() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000), List.of(balance(300_000), card("c1", 700_000)));
        assertThat(p.balanceAmount()).isEqualTo(Money.won(300_000));
        assertThat(p.externalAllocations()).hasSize(1);
    }

    @Test @DisplayName("markPaid → PAID, paidAt 설정")
    void mark_paid() {
        Payment p = Payment.create("u1", "ord1", Money.won(1000), List.of(balance(1000)));
        p.markPaid(java.time.Instant.parse("2026-05-25T00:00:00Z"));
        assertThat(p.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(p.paidAt()).isNotNull();
    }
}
```

- [ ] **Step 2: 실패 확인** — Run: `./gradlew :payment:test --tests "com.payment.payment.domain.PaymentTest"` → FAIL

- [ ] **Step 3: 최소 구현**

```java
package com.payment.payment.domain;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public class Payment {
    private final String id;
    private final String userId;
    private final String orderId;
    private final Money totalAmount;
    private final List<PaymentAllocation> allocations;
    private PaymentStatus status;
    private Instant paidAt;

    Payment(String id, String userId, String orderId, Money totalAmount,
            List<PaymentAllocation> allocations, PaymentStatus status, Instant paidAt) {
        if (allocations == null || allocations.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "allocations가 비어 있습니다");
        }
        Money sum = allocations.stream().map(PaymentAllocation::amount).reduce(Money.ZERO, Money::plus);
        if (!sum.equals(totalAmount)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "분배 합이 total_amount와 다릅니다");
        }
        if (allocations.stream().filter(a -> a.methodType() == PaymentMethodType.BALANCE).count() > 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "BALANCE 수단은 최대 1개입니다");
        }
        long distinctExternal = allocations.stream().filter(PaymentAllocation::isExternal)
                .map(PaymentAllocation::methodId).distinct().count();
        long external = allocations.stream().filter(PaymentAllocation::isExternal).count();
        if (distinctExternal != external) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "method_id가 중복됩니다");
        }
        this.id = id; this.userId = userId; this.orderId = orderId; this.totalAmount = totalAmount;
        this.allocations = List.copyOf(allocations); this.status = status; this.paidAt = paidAt;
    }

    public static Payment create(String userId, String orderId, Money totalAmount, List<PaymentAllocation> allocations) {
        return new Payment(UUID.randomUUID().toString(), userId, orderId, totalAmount, allocations, PaymentStatus.PENDING, null);
    }
    public static Payment restore(String id, String userId, String orderId, Money totalAmount,
            List<PaymentAllocation> allocations, PaymentStatus status, Instant paidAt) {
        return new Payment(id, userId, orderId, totalAmount, allocations, status, paidAt);
    }

    public Money balanceAmount() {
        return allocations.stream().filter(a -> a.methodType() == PaymentMethodType.BALANCE)
                .map(PaymentAllocation::amount).findFirst().orElse(Money.ZERO);
    }
    public List<PaymentAllocation> externalAllocations() {
        return allocations.stream().filter(PaymentAllocation::isExternal).toList();
    }

    public void markPaid(Instant at) { this.status = PaymentStatus.PAID; this.paidAt = at; }
    public void markFailed() { this.status = PaymentStatus.FAILED; }
    public void markFailedRefunded() { this.status = PaymentStatus.FAILED_REFUNDED; }

    public String id() { return id; }
    public String userId() { return userId; }
    public String orderId() { return orderId; }
    public Money totalAmount() { return totalAmount; }
    public List<PaymentAllocation> allocations() { return allocations; }
    public PaymentStatus status() { return status; }
    public Instant paidAt() { return paidAt; }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add Payment aggregate with split-payment invariants (TDD)"`

---

### Task 4: `PaymentIdempotency` 도메인 (멱등 분기)

**Files:**
- Create: `.../domain/PaymentIdempotency.java`
- Test: `.../domain/PaymentIdempotencyTest.java`

분기(설계 4-1 시나리오 15): `resolve()`가 신규처리/기존응답/진행중을 표현.

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.payment.payment.domain;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentIdempotencyTest {

    @Test @DisplayName("COMPLETED는 기존 결과 반환(REPLAY)")
    void completed_replays() {
        PaymentIdempotency i = PaymentIdempotency.restore("k", "u1", "ord", "PAY1", IdempotencyStatus.COMPLETED, PgCallStatus.SUCCESS);
        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.REPLAY);
    }

    @Test @DisplayName("PENDING은 진행중(409)")
    void pending_in_progress() {
        PaymentIdempotency i = PaymentIdempotency.restore("k", "u1", "ord", "PAY1", IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED);
        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.IN_PROGRESS);
    }

    @Test @DisplayName("FAILED는 신규 재시도 허용")
    void failed_allows_new() {
        PaymentIdempotency i = PaymentIdempotency.restore("k", "u1", "ord", null, IdempotencyStatus.FAILED, PgCallStatus.FAILED);
        assertThat(i.resolve()).isEqualTo(IdempotencyDecision.PROCEED_NEW);
    }
}
```

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 최소 구현** — `IdempotencyDecision` enum(`PROCEED_NEW`,`REPLAY`,`IN_PROGRESS`) + 도메인:

```java
package com.payment.payment.domain;

public class PaymentIdempotency {
    private final String idempotencyKey;
    private final String userId;
    private final String orderId;
    private String paymentId;
    private IdempotencyStatus status;
    private PgCallStatus pgCallStatus;

    PaymentIdempotency(String key, String userId, String orderId, String paymentId,
                       IdempotencyStatus status, PgCallStatus pgCallStatus) {
        this.idempotencyKey = key; this.userId = userId; this.orderId = orderId;
        this.paymentId = paymentId; this.status = status; this.pgCallStatus = pgCallStatus;
    }

    public static PaymentIdempotency start(String key, String userId, String orderId, String paymentId) {
        return new PaymentIdempotency(key, userId, orderId, paymentId, IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED);
    }
    public static PaymentIdempotency restore(String key, String userId, String orderId, String paymentId,
            IdempotencyStatus status, PgCallStatus pgCallStatus) {
        return new PaymentIdempotency(key, userId, orderId, paymentId, status, pgCallStatus);
    }

    public IdempotencyDecision resolve() {
        return switch (status) {
            case COMPLETED -> IdempotencyDecision.REPLAY;
            case PENDING -> IdempotencyDecision.IN_PROGRESS;
            case FAILED -> IdempotencyDecision.PROCEED_NEW;
        };
    }

    public void markCalling(String pgIdempotencyKey) { this.pgCallStatus = PgCallStatus.CALLING; }
    public void markPgStatus(PgCallStatus s) { this.pgCallStatus = s; }
    public void complete() { this.status = IdempotencyStatus.COMPLETED; this.pgCallStatus = PgCallStatus.SUCCESS; }
    public void fail() { this.status = IdempotencyStatus.FAILED; }

    public String idempotencyKey() { return idempotencyKey; }
    public String userId() { return userId; }
    public String orderId() { return orderId; }
    public String paymentId() { return paymentId; }
    public IdempotencyStatus status() { return status; }
    public PgCallStatus pgCallStatus() { return pgCallStatus; }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add PaymentIdempotency with replay/in-progress branching (TDD)"`

---

## Phase B — balance_history + 애플리케이션 포트 + 서비스

### Task 5: `BalanceHistory` 도메인 + 포트 (balance 컨텍스트)

**Files:**
- Create: `com/payment/balance/domain/BalanceAction.java`(enum `CHARGE/PAYMENT/REFUND/ROLLBACK`), `.../domain/BalanceHistory.java`
- Create: `com/payment/balance/application/port/out/BalanceHistoryRepository.java`
- Test: `com/payment/balance/domain/BalanceHistoryTest.java`

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.payment.balance.domain;

import static org.assertj.core.api.Assertions.*;
import com.payment.common.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BalanceHistoryTest {
    @Test @DisplayName("결제 차감은 음수 변화량으로 기록된다")
    void payment_is_negative_change() {
        BalanceHistory h = BalanceHistory.payment("u1", Money.won(30_000), Money.won(70_000), "PAY1");
        assertThat(h.action()).isEqualTo(BalanceAction.PAYMENT);
        assertThat(h.amountChange()).isEqualTo(-30_000);
        assertThat(h.balanceAfter()).isEqualTo(70_000);
        assertThat(h.paymentId()).isEqualTo("PAY1");
    }

    @Test @DisplayName("원복은 양수 변화량(ROLLBACK)으로 기록된다")
    void rollback_is_positive_change() {
        BalanceHistory h = BalanceHistory.rollback("u1", Money.won(30_000), Money.won(100_000), "PAY1");
        assertThat(h.action()).isEqualTo(BalanceAction.ROLLBACK);
        assertThat(h.amountChange()).isEqualTo(30_000);
    }
}
```

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 최소 구현**

```java
package com.payment.balance.domain;
public enum BalanceAction { CHARGE, PAYMENT, REFUND, ROLLBACK }
```
```java
package com.payment.balance.domain;
import com.payment.common.Money;

/** 잔액 변경 감사 로그 (설계 7-2 balance_history, INSERT-only). amountChange는 부호 포함. */
public class BalanceHistory {
    private final String userId;
    private final BalanceAction action;
    private final long amountChange;     // +충전/원복, -결제
    private final long balanceAfter;
    private final String paymentId;      // nullable
    private final String chargeId;       // nullable

    private BalanceHistory(String userId, BalanceAction action, long amountChange,
                           long balanceAfter, String paymentId, String chargeId) {
        this.userId = userId; this.action = action; this.amountChange = amountChange;
        this.balanceAfter = balanceAfter; this.paymentId = paymentId; this.chargeId = chargeId;
    }
    public static BalanceHistory payment(String userId, Money amount, Money balanceAfter, String paymentId) {
        return new BalanceHistory(userId, BalanceAction.PAYMENT, -amount.value(), balanceAfter.value(), paymentId, null);
    }
    public static BalanceHistory rollback(String userId, Money amount, Money balanceAfter, String paymentId) {
        return new BalanceHistory(userId, BalanceAction.ROLLBACK, amount.value(), balanceAfter.value(), paymentId, null);
    }
    public static BalanceHistory charge(String userId, Money amount, Money balanceAfter, String chargeId) {
        return new BalanceHistory(userId, BalanceAction.CHARGE, amount.value(), balanceAfter.value(), null, chargeId);
    }
    public String userId() { return userId; }
    public BalanceAction action() { return action; }
    public long amountChange() { return amountChange; }
    public long balanceAfter() { return balanceAfter; }
    public String paymentId() { return paymentId; }
    public String chargeId() { return chargeId; }
}
```
```java
package com.payment.balance.application.port.out;
import com.payment.balance.domain.BalanceHistory;
/** 잔액 변경 감사 로그 append (설계 7-2). 호출 트랜잭션에 참여만 한다. */
public interface BalanceHistoryRepository { void append(BalanceHistory history); }
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(balance): add BalanceHistory audit domain and port (TDD)"`

---

### Task 6: 애플리케이션 포트 (in/out) + 명령/결과/이벤트 DTO

**Files (모두 Create):**
- in: `.../application/port/in/ProcessPaymentUseCase.java`, `ProcessPaymentCommand.java`, `PaymentResult.java`
- out: `PgPort.java`, `PaymentRepository.java`, `PaymentIdempotencyRepository.java`, `CompensationFailureRecorder.java`, `PaymentEventPublisher.java`, `PaymentCreatedEvent.java`

테스트 없음(인터페이스/DTO). 다음 Task의 서비스 테스트가 이들을 검증한다.

- [ ] **Step 1: in 포트 + DTO 작성**

```java
package com.payment.payment.application.port.in;
public interface ProcessPaymentUseCase {
    PaymentResult process(ProcessPaymentCommand command);
}
```
```java
package com.payment.payment.application.port.in;
import com.payment.common.Money;
import com.payment.payment.domain.PaymentMethodType;
import java.util.List;

public record ProcessPaymentCommand(
        String idempotencyKey, String userId, String orderId, Money totalAmount, List<Line> allocations) {
    public record Line(PaymentMethodType methodType, String methodId, Money amount) {}
}
```
```java
package com.payment.payment.application.port.in;
import com.payment.common.Money;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;

public record PaymentResult(String paymentId, PaymentStatus status, String userId, String orderId,
        Money totalAmount, Instant paidAt, List<Alloc> allocations) {
    public record Alloc(PaymentMethodType methodType, String methodId, Money amount,
            AllocationStatus status, String pgTransactionId) {}
}
```

- [ ] **Step 2: out 포트 작성**

```java
package com.payment.payment.application.port.out;
import com.payment.common.Money;
import com.payment.payment.domain.PaymentMethodType;

/** PG 청구. 트랜잭션 밖에서 호출(설계 6-1). 환불(refund)은 2차 환불 도메인 — 본 포트에 없음. */
public interface PgPort {
    PgChargeResult charge(PgChargeRequest request);

    record PgChargeRequest(String pgIdempotencyKey, PaymentMethodType methodType, String methodId, Money amount) {}
    record PgChargeResult(Outcome outcome, String pgTransactionId, String declineReason) {
        public enum Outcome { APPROVED, DECLINED, TIMEOUT }
        public static PgChargeResult approved(String txId) { return new PgChargeResult(Outcome.APPROVED, txId, null); }
        public static PgChargeResult declined(String reason) { return new PgChargeResult(Outcome.DECLINED, null, reason); }
        public static PgChargeResult timeout() { return new PgChargeResult(Outcome.TIMEOUT, null, null); }
    }
}
```
```java
package com.payment.payment.application.port.out;
import com.payment.payment.domain.Payment;
import java.util.Optional;
public interface PaymentRepository {
    void save(Payment payment);                 // INSERT/UPDATE (allocations 포함)
    Optional<Payment> findById(String paymentId);
}
```
```java
package com.payment.payment.application.port.out;
import com.payment.payment.domain.PaymentIdempotency;
import java.util.Optional;
public interface PaymentIdempotencyRepository {
    Optional<PaymentIdempotency> find(String userId, String idempotencyKey);
    void save(PaymentIdempotency idempotency);
}
```
```java
package com.payment.payment.application.port.out;
import com.payment.common.Money;
/** compensating_transaction_failures 기록 (설계 6-4, 수동 복구 채널). */
public interface CompensationFailureRecorder {
    void record(String paymentId, FailureType type, Money amount);
    enum FailureType { BALANCE_ROLLBACK, USAGE_ROLLBACK, PG_REFUND_CALL }
}
```
```java
package com.payment.payment.application.port.out;
public interface PaymentEventPublisher { void paymentCreated(PaymentCreatedEvent event); }
```
```java
package com.payment.payment.application.port.out;
/** 최소 payload — 정식 계약 DTO는 로드맵 item 3(:event-contracts)에서 정리. */
public record PaymentCreatedEvent(String paymentId, String userId, String orderId, long totalAmount, String occurredAt) {}
```

- [ ] **Step 3: 컴파일 확인** — Run: `./gradlew :payment:compileJava` → SUCCESS
- [ ] **Step 4: Commit** — `git commit -m "feat(payment): add use case and out ports for payment processing"`

---

### Task 7: `PaymentTransactionService.reserve()` — TX1 (잔액·한도 차감)

**Files:**
- Create: `.../application/PaymentTransactionService.java` (점진적으로 메서드 추가)
- Test: `.../application/PaymentTransactionServiceTest.java` (out 포트 mock; `@Transactional`은 단위테스트에서 no-op)

`reserve()`: payment 생성·저장 → idempotency PENDING 저장 → 잔액→한도 **고정 순서 락** → `usage.use`(한도검사) → `balance.deduct`(잔액검사) → `balanceHistory.append(PAYMENT)` → 저장. 반환 `Reservation`(payment, pgIdempotencyKey, 외부 allocation 목록).

> 락 순서는 설계 9-2대로 **잔액(balance) 먼저, 한도(usage) 나중**. 검사 순서는 무관하나 락 획득 순서를 고정한다.

- [ ] **Step 1: 실패 테스트 작성** (happy + 잔액부족 + 한도초과)

```java
package com.payment.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.payment.balance.application.port.out.*;
import com.payment.balance.domain.*;
import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentCommand.Line;
import com.payment.payment.application.port.out.*;
import com.payment.payment.domain.PaymentMethodType;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class PaymentTransactionServiceTest {

    BalanceRepository balanceRepo = mock(BalanceRepository.class);
    DailyUsageRepository usageRepo = mock(DailyUsageRepository.class);
    DailyLimitRepository limitRepo = mock(DailyLimitRepository.class);
    BalanceHistoryRepository historyRepo = mock(BalanceHistoryRepository.class);
    PaymentRepository paymentRepo = mock(PaymentRepository.class);
    PaymentIdempotencyRepository idempotencyRepo = mock(PaymentIdempotencyRepository.class);
    CompensationFailureRecorder compensationRecorder = mock(CompensationFailureRecorder.class);
    Clock clock = Clock.fixed(Instant.parse("2026-05-25T00:00:00Z"), ZoneOffset.UTC);

    PaymentTransactionService service = new PaymentTransactionService(
            balanceRepo, usageRepo, limitRepo, historyRepo, paymentRepo, idempotencyRepo, compensationRecorder, clock);

    ProcessPaymentCommand splitCommand() {
        return new ProcessPaymentCommand("idem-1", "u1", "ord1", Money.won(1_000_000),
                List.of(new Line(PaymentMethodType.BALANCE, null, Money.won(300_000)),
                        new Line(PaymentMethodType.CARD, "c1", Money.won(700_000))));
    }

    @BeforeEach void stubs() {
        when(limitRepo.findByUserId("u1")).thenReturn(Optional.of(new UserDailyLimit("u1", Money.won(5_000_000))));
        when(usageRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(UserDailyUsage.create("u1", LocalDate.of(2026,5,25))));
    }

    @Test @DisplayName("정상: 잔액 차감 + 한도 누적 + history 기록 + payment/idempotency 저장")
    void reserve_happy() {
        when(balanceRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(new UserBalance("u1", Money.won(1_000_000))));

        var reservation = service.reserve(splitCommand());

        ArgumentCaptor<UserBalance> balCap = ArgumentCaptor.forClass(UserBalance.class);
        verify(balanceRepo).save(balCap.capture());
        assertThat(balCap.getValue().balance()).isEqualTo(Money.won(700_000)); // 100만-30만
        verify(historyRepo).append(any(BalanceHistory.class));
        verify(paymentRepo).save(any());
        verify(idempotencyRepo).save(any());
        assertThat(reservation.externalAllocations()).hasSize(1);
    }

    @Test @DisplayName("잔액 부족: INSUFFICIENT_BALANCE, 저장 없음")
    void reserve_insufficient_balance() {
        when(balanceRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(new UserBalance("u1", Money.won(100_000))));
        assertThatThrownBy(() -> service.reserve(splitCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INSUFFICIENT_BALANCE);
    }

    @Test @DisplayName("한도 초과: DAILY_LIMIT_EXCEEDED")
    void reserve_limit_exceeded() {
        when(balanceRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(new UserBalance("u1", Money.won(1_000_000))));
        when(limitRepo.findByUserId("u1")).thenReturn(Optional.of(new UserDailyLimit("u1", Money.won(500_000))));
        assertThatThrownBy(() -> service.reserve(splitCommand()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED);
    }
}
```

- [ ] **Step 2: 실패 확인** → FAIL (PaymentTransactionService 없음)

- [ ] **Step 3: 최소 구현** — `reserve()` + `Reservation` record. 신규 사용자(balance/usage row 없음)는 `create`로 보정. 락 순서 잔액→한도.

```java
package com.payment.payment.application;

import com.payment.balance.application.port.out.*;
import com.payment.balance.domain.*;
import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.out.*;
import com.payment.payment.domain.*;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PaymentTransactionService {
    private final BalanceRepository balanceRepo;
    private final DailyUsageRepository usageRepo;
    private final DailyLimitRepository limitRepo;
    private final BalanceHistoryRepository historyRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentIdempotencyRepository idempotencyRepo;
    private final CompensationFailureRecorder compensationRecorder;
    private final Clock clock;

    public record Reservation(String paymentId, String idempotencyKey, String pgIdempotencyKey,
                              List<PaymentAllocation> externalAllocations) {}

    @Transactional
    public Reservation reserve(ProcessPaymentCommand cmd) {
        LocalDate today = LocalDate.now(clock);
        List<PaymentAllocation> allocations = cmd.allocations().stream().map(l ->
                l.methodType() == PaymentMethodType.BALANCE
                        ? PaymentAllocation.balance(l.amount())
                        : PaymentAllocation.external(l.methodType(), l.methodId(), l.amount())).toList();
        Payment payment = Payment.create(cmd.userId(), cmd.orderId(), cmd.totalAmount(), allocations);

        String pgIdempotencyKey = UUID.randomUUID().toString();
        idempotencyRepo.save(PaymentIdempotency.start(cmd.idempotencyKey(), cmd.userId(), cmd.orderId(), payment.id()));
        paymentRepo.save(payment);

        // 고정 순서 락: 잔액 → 한도 (설계 9-2)
        UserBalance balance = balanceRepo.findByUserIdWithPessimisticLock(cmd.userId())
                .orElseGet(() -> UserBalance.create(cmd.userId()));
        UserDailyUsage usage = usageRepo.findByUserIdWithPessimisticLock(cmd.userId())
                .orElseGet(() -> UserDailyUsage.create(cmd.userId(), today));
        UserDailyLimit limit = limitRepo.findByUserId(cmd.userId())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST, "사용자 한도 설정이 없습니다"));

        // 한도 누적(검사 포함) — 차감액은 total_amount (설계 0장: 결제 전액이 한도에 누적)
        usage.use(cmd.totalAmount(), limit, today);
        // 잔액 차감(부족 시 예외) — BALANCE allocation 금액만
        Money balanceAmount = payment.balanceAmount();
        if (balanceAmount.isPositive()) {
            balance.deduct(balanceAmount);
            balanceRepo.save(balance);
            historyRepo.append(BalanceHistory.payment(cmd.userId(), balanceAmount, balance.balance(), payment.id()));
        }
        usageRepo.save(usage);

        return new Reservation(payment.id(), cmd.idempotencyKey(), pgIdempotencyKey, payment.externalAllocations());
    }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add reserve() TX1 — lock-order deduct with limit/balance checks (TDD)"`

> ⚠️ 한도 누적 단위: 설계 0장/시나리오 19("한도 100만 누적 원복")에 따라 **total_amount 전액**을 한도에 누적한다(카드분 포함). reserve 테스트의 한도 stub(500만)도 이를 전제.

---

### Task 8: `PaymentTransactionService` — `markPgCalling`/`markPgStatus`/`confirm`/`compensate`

**Files:** Modify `PaymentTransactionService.java`; Test 확장 `PaymentTransactionServiceTest.java`

- [ ] **Step 1: 실패 테스트 추가** — confirm(성공), compensate(원복 + PG_REFUND_CALL 기록)

```java
    @Test @DisplayName("confirm: allocation SETTLED + payment PAID + idempotency COMPLETED")
    void confirm_marks_paid() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(PaymentAllocation.balance(Money.won(300_000)), PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000))));
        when(paymentRepo.findById(p.id())).thenReturn(Optional.of(p));
        when(idempotencyRepo.find("u1", "idem-1")).thenReturn(Optional.of(PaymentIdempotency.start("idem-1","u1","ord1",p.id())));

        var ext = p.externalAllocations().get(0);
        var result = service.confirm(p.id(), "u1", "idem-1",
                List.of(new PaymentTransactionService.Charged(ext.id(), "PG_tx_1")));

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(paymentRepo).save(argThat(saved -> saved.status() == PaymentStatus.PAID));
    }

    @Test @DisplayName("compensate(PG_DECLINED, 청구된 카드 있음): 잔액·한도 원복 + PG_REFUND_CALL 기록 + payment FAILED")
    void compensate_with_charged_records_refund_failure() {
        Payment p = Payment.create("u1", "ord1", Money.won(1_000_000),
                List.of(PaymentAllocation.balance(Money.won(300_000)), PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000))));
        when(paymentRepo.findById(p.id())).thenReturn(Optional.of(p));
        when(idempotencyRepo.find("u1","idem-1")).thenReturn(Optional.of(PaymentIdempotency.start("idem-1","u1","ord1",p.id())));
        when(balanceRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(new UserBalance("u1", Money.won(700_000))));
        when(usageRepo.findByUserIdWithPessimisticLock("u1")).thenReturn(Optional.of(new UserDailyUsage("u1", Money.won(1_000_000), LocalDate.of(2026,5,25))));

        var ext = p.externalAllocations().get(0);
        service.compensate(p.id(), "u1", "idem-1", PaymentStatus.FAILED,
                List.of(new PaymentTransactionService.Charged(ext.id(), "PG_tx_1")));

        verify(balanceRepo).save(argThat(b -> b.balance().equals(Money.won(1_000_000)))); // 70만+30만 원복
        verify(compensationRecorder).record(eq(p.id()), eq(CompensationFailureRecorder.FailureType.PG_REFUND_CALL), eq(Money.won(700_000)));
        verify(paymentRepo).save(argThat(saved -> saved.status() == PaymentStatus.FAILED));
    }
```

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 구현 추가** — `Charged` record + 4개 메서드

```java
    public record Charged(String allocationId, String pgTransactionId) {}

    @Transactional
    public void markPgCalling(String userId, String idempotencyKey, String pgIdempotencyKey) {
        PaymentIdempotency i = idempotencyRepo.find(userId, idempotencyKey).orElseThrow();
        i.markCalling(pgIdempotencyKey);
        idempotencyRepo.save(i);
    }

    @Transactional
    public void markPgStatus(String userId, String idempotencyKey, PgCallStatus status) {
        PaymentIdempotency i = idempotencyRepo.find(userId, idempotencyKey).orElseThrow();
        i.markPgStatus(status);
        idempotencyRepo.save(i);
    }

    @Transactional
    public com.payment.payment.application.port.in.PaymentResult confirm(
            String paymentId, String userId, String idempotencyKey, List<Charged> charged) {
        Payment p = paymentRepo.findById(paymentId).orElseThrow();
        var byId = charged.stream().collect(java.util.stream.Collectors.toMap(Charged::allocationId, Charged::pgTransactionId));
        p.allocations().forEach(a -> a.settle(byId.get(a.id())));   // BALANCE는 pgTxId=null
        p.markPaid(clock.instant());
        paymentRepo.save(p);
        PaymentIdempotency i = idempotencyRepo.find(userId, idempotencyKey).orElseThrow();
        i.complete();
        idempotencyRepo.save(i);
        return PaymentResultMapper.toResult(p);
    }

    @Transactional
    public void compensate(String paymentId, String userId, String idempotencyKey,
                           PaymentStatus finalStatus, List<Charged> chargedToRefund) {
        Payment p = paymentRepo.findById(paymentId).orElseThrow();
        LocalDate today = LocalDate.now(clock);

        // 잔액·한도 내부 원복 (고정 순서 락)
        UserBalance balance = balanceRepo.findByUserIdWithPessimisticLock(userId).orElseThrow();
        UserDailyUsage usage = usageRepo.findByUserIdWithPessimisticLock(userId).orElseThrow();
        Money balanceAmount = p.balanceAmount();
        if (balanceAmount.isPositive()) {
            balance.rollback(balanceAmount);
            balanceRepo.save(balance);
            historyRepo.append(BalanceHistory.rollback(userId, balanceAmount, balance.balance(), p.id()));
        }
        usage.rollback(p.totalAmount(), today);
        usageRepo.save(usage);

        // 이미 청구된 카드: PG 회수 보류 → 실패 기록(2차 환불 도메인이 회복)
        chargedToRefund.forEach(c -> {
            Money amt = p.allocations().stream().filter(a -> a.id().equals(c.allocationId()))
                    .findFirst().map(PaymentAllocation::amount).orElse(Money.ZERO);
            compensationRecorder.record(p.id(), CompensationFailureRecorder.FailureType.PG_REFUND_CALL, amt);
        });

        if (finalStatus == PaymentStatus.FAILED_REFUNDED) p.markFailedRefunded(); else p.markFailed();
        paymentRepo.save(p);
        PaymentIdempotency i = idempotencyRepo.find(userId, idempotencyKey).orElseThrow();
        i.fail();
        idempotencyRepo.save(i);
    }
```

`PaymentResultMapper` (application 내 package-private util):

```java
package com.payment.payment.application;
import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.domain.Payment;
final class PaymentResultMapper {
    private PaymentResultMapper() {}
    static PaymentResult toResult(Payment p) {
        return new PaymentResult(p.id(), p.status(), p.userId(), p.orderId(), p.totalAmount(), p.paidAt(),
            p.allocations().stream().map(a -> new PaymentResult.Alloc(
                a.methodType(), a.methodId(), a.amount(), a.status(), a.pgTransactionId())).toList());
    }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add confirm/compensate/pg-status TX boundaries (TDD)"`

---

### Task 9: `ProcessPaymentService` — orchestration (happy + 멱등 분기 + 거절/타임아웃/보상)

**Files:**
- Create: `.../application/ProcessPaymentService.java`
- Test: `.../application/ProcessPaymentServiceTest.java` (mock: `PaymentTransactionService`, `PgPort`, `PaymentIdempotencyRepository`, `PaymentRepository`, `PaymentEventPublisher`)

분기:
- 멱등성 `find`: REPLAY→기존 result(200) / IN_PROGRESS→409 / 없음·PROCEED_NEW→신규
- 신규: `reserve` → `markPgCalling` → 각 외부 allocation `pgPort.charge`:
  - 전부 APPROVED → `markPgStatus(SUCCESS)` → `confirm` → `paymentCreated` 발행 → result
  - 하나라도 DECLINED → `markPgStatus(FAILED)` → `compensate(FAILED, 이미 APPROVED된 것 목록)` → `BusinessException(PG_DECLINED)`
  - 하나라도 TIMEOUT → `markPgStatus(UNKNOWN)` → `BusinessException(INTERNAL_ERROR)` (보정 배치 회복, **원복 안 함**)

- [ ] **Step 1: 실패 테스트 작성**

```java
package com.payment.payment.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentCommand.Line;
import com.payment.payment.application.port.out.*;
import com.payment.payment.application.port.out.PgPort.PgChargeResult;
import com.payment.payment.domain.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;

class ProcessPaymentServiceTest {

    PaymentTransactionService tx = mock(PaymentTransactionService.class);
    PgPort pgPort = mock(PgPort.class);
    PaymentIdempotencyRepository idempotencyRepo = mock(PaymentIdempotencyRepository.class);
    PaymentRepository paymentRepo = mock(PaymentRepository.class);
    PaymentEventPublisher publisher = mock(PaymentEventPublisher.class);

    ProcessPaymentService service = new ProcessPaymentService(tx, pgPort, idempotencyRepo, paymentRepo, publisher);

    ProcessPaymentCommand cmd() {
        return new ProcessPaymentCommand("idem-1","u1","ord1", Money.won(1_000_000),
            List.of(new Line(PaymentMethodType.BALANCE,null,Money.won(300_000)),
                    new Line(PaymentMethodType.CARD,"c1",Money.won(700_000))));
    }
    PaymentAllocation ext() { return PaymentAllocation.external(PaymentMethodType.CARD, "c1", Money.won(700_000)); }

    @BeforeEach void newRequest() { when(idempotencyRepo.find("u1","idem-1")).thenReturn(Optional.empty()); }

    @Test @DisplayName("정상: reserve→PG 성공→confirm→이벤트 발행")
    void happy_path() {
        var ext = ext();
        when(tx.reserve(any())).thenReturn(new PaymentTransactionService.Reservation("PAY1","idem-1","pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.approved("PG_tx_1"));
        when(tx.confirm(eq("PAY1"), eq("u1"), eq("idem-1"), anyList()))
            .thenReturn(new com.payment.payment.application.port.in.PaymentResult("PAY1", PaymentStatus.PAID,"u1","ord1",Money.won(1_000_000),null,List.of()));

        var result = service.process(cmd());

        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(tx).markPgCalling("u1","idem-1","pg-1");
        verify(tx).markPgStatus("u1","idem-1", PgCallStatus.SUCCESS);
        verify(publisher).paymentCreated(any());
    }

    @Test @DisplayName("PG 거절(청구 전): 보상(FAILED) + PG_DECLINED, 환불목록 비어있음")
    void pg_declined() {
        var ext = ext();
        when(tx.reserve(any())).thenReturn(new PaymentTransactionService.Reservation("PAY1","idem-1","pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.declined("도난카드"));

        assertThatThrownBy(() -> service.process(cmd()))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.PG_DECLINED);
        verify(tx).markPgStatus("u1","idem-1", PgCallStatus.FAILED);
        verify(tx).compensate(eq("PAY1"), eq("u1"), eq("idem-1"), eq(PaymentStatus.FAILED), argThat(List::isEmpty));
        verify(publisher, never()).paymentCreated(any());
    }

    @Test @DisplayName("타임아웃: UNKNOWN 마킹 + 500, 원복 안 함(보정 배치 회복)")
    void pg_timeout() {
        var ext = ext();
        when(tx.reserve(any())).thenReturn(new PaymentTransactionService.Reservation("PAY1","idem-1","pg-1", List.of(ext)));
        when(pgPort.charge(any())).thenReturn(PgChargeResult.timeout());

        assertThatThrownBy(() -> service.process(cmd()))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INTERNAL_ERROR);
        verify(tx).markPgStatus("u1","idem-1", PgCallStatus.UNKNOWN);
        verify(tx, never()).compensate(any(), any(), any(), any(), anyList());
    }

    @Test @DisplayName("멱등 진행중(PENDING): 409 PAYMENT_IN_PROGRESS")
    void idempotent_in_progress() {
        when(idempotencyRepo.find("u1","idem-1"))
            .thenReturn(Optional.of(PaymentIdempotency.restore("idem-1","u1","ord1","PAY1", IdempotencyStatus.PENDING, PgCallStatus.NOT_CALLED)));
        assertThatThrownBy(() -> service.process(cmd()))
            .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.PAYMENT_IN_PROGRESS);
        verify(tx, never()).reserve(any());
    }

    @Test @DisplayName("멱등 완료(COMPLETED): 기존 결제 결과 반환, 재처리 없음")
    void idempotent_replay() {
        when(idempotencyRepo.find("u1","idem-1"))
            .thenReturn(Optional.of(PaymentIdempotency.restore("idem-1","u1","ord1","PAY1", IdempotencyStatus.COMPLETED, PgCallStatus.SUCCESS)));
        Payment paid = Payment.restore("PAY1","u1","ord1",Money.won(1_000_000),
            List.of(PaymentAllocation.balance(Money.won(1_000_000))), PaymentStatus.PAID, java.time.Instant.now());
        when(paymentRepo.findById("PAY1")).thenReturn(Optional.of(paid));

        var result = service.process(cmd());
        assertThat(result.status()).isEqualTo(PaymentStatus.PAID);
        verify(tx, never()).reserve(any());
    }
}
```

- [ ] **Step 2: 실패 확인** → FAIL

- [ ] **Step 3: 최소 구현**

```java
package com.payment.payment.application;

import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.*;
import com.payment.payment.application.port.out.*;
import com.payment.payment.application.port.out.PgPort.PgChargeRequest;
import com.payment.payment.application.port.out.PgPort.PgChargeResult;
import com.payment.payment.application.PaymentTransactionService.Charged;
import com.payment.payment.domain.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProcessPaymentService implements ProcessPaymentUseCase {

    private final PaymentTransactionService tx;
    private final PgPort pgPort;
    private final PaymentIdempotencyRepository idempotencyRepo;
    private final PaymentRepository paymentRepo;
    private final PaymentEventPublisher publisher;

    @Override
    public PaymentResult process(ProcessPaymentCommand cmd) {
        var existing = idempotencyRepo.find(cmd.userId(), cmd.idempotencyKey());
        if (existing.isPresent()) {
            switch (existing.get().resolve()) {
                case REPLAY -> { return PaymentResultMapper.toResult(
                        paymentRepo.findById(existing.get().paymentId()).orElseThrow()); }
                case IN_PROGRESS -> throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS);
                case PROCEED_NEW -> { /* 새 시도 허용 */ }
            }
        }

        PaymentTransactionService.Reservation r;
        try {
            r = tx.reserve(cmd);
        } catch (PessimisticLockingFailureException | CannotAcquireLockException e) {
            throw new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS); // 설계 9-4 락 대기 초과 → 409
        }

        tx.markPgCalling(cmd.userId(), cmd.idempotencyKey(), r.pgIdempotencyKey());

        List<Charged> charged = new ArrayList<>();
        for (PaymentAllocation a : r.externalAllocations()) {
            PgChargeResult res = pgPort.charge(new PgChargeRequest(r.pgIdempotencyKey(), a.methodType(), a.methodId(), a.amount()));
            switch (res.outcome()) {
                case APPROVED -> charged.add(new Charged(a.id(), res.pgTransactionId()));
                case DECLINED -> {
                    tx.markPgStatus(cmd.userId(), cmd.idempotencyKey(), PgCallStatus.FAILED);
                    tx.compensate(r.paymentId(), cmd.userId(), cmd.idempotencyKey(), PaymentStatus.FAILED, charged);
                    throw new BusinessException(ErrorCode.PG_DECLINED, res.declineReason());
                }
                case TIMEOUT -> {
                    tx.markPgStatus(cmd.userId(), cmd.idempotencyKey(), PgCallStatus.UNKNOWN);
                    throw new BusinessException(ErrorCode.INTERNAL_ERROR); // 보정 배치 회복(설계 20), 원복 금지
                }
            }
        }

        tx.markPgStatus(cmd.userId(), cmd.idempotencyKey(), PgCallStatus.SUCCESS);
        PaymentResult result;
        try {
            result = tx.confirm(r.paymentId(), cmd.userId(), cmd.idempotencyKey(), charged);
        } catch (RuntimeException e) {  // 시나리오 22: TX2 실패 → 내부 원복 + 청구분 회수 보류 기록
            tx.compensate(r.paymentId(), cmd.userId(), cmd.idempotencyKey(), PaymentStatus.FAILED_REFUNDED, charged);
            throw new BusinessException(ErrorCode.INCONSISTENT_STATE);
        }

        publisher.paymentCreated(new PaymentCreatedEvent(
                result.paymentId(), result.userId(), result.orderId(), result.totalAmount().value(), Instant.now().toString()));
        return result;
    }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add ProcessPaymentService orchestration with idempotency/PG branching (TDD)"`

> 시나리오 23(다수단 부분 실패)은 위 DECLINED 분기가 `charged`(이미 APPROVED된 카드)를 `compensate`에 넘겨 자동 처리됨 — Task 8의 compensate 테스트가 PG_REFUND_CALL 기록을 이미 검증. 시나리오 22(TX2 실패)는 confirm catch 분기 + Task 8 compensate(FAILED_REFUNDED) 경로.

---

## Phase C — 어댑터 (Testcontainers / 슬라이스 테스트)

> 영속성 어댑터는 기존 balance 패턴을 그대로 따른다: 엔티티 package-private + `from`/`toDomain`, `created_at/updated_at` 미매핑, JPA 리포지토리 package-private(`@Lock`+`@Query`로 비관적 락), 어댑터 `@Repository @RequiredArgsConstructor`가 public 포트 구현. 통합테스트는 `AbstractMySqlContainerTest` 상속 + public 포트 + `JdbcTemplate` truncate.

### Task 10: payment 영속 어댑터 (payments + payment_allocation)

**Files (Create):** `adapter/out/persistence/payment/` 아래 `PaymentJpaEntity`, `PaymentAllocationJpaEntity`, `PaymentJpaRepository`, `PaymentAllocationJpaRepository`, `PaymentPersistenceAdapter`
**Test:** `adapter/out/persistence/PaymentPersistenceAdapterIntegrationTest` (`AbstractMySqlContainerTest` 상속)

`Payment` 1 : N `PaymentAllocation`. `save(payment)`는 payment + allocations 모두 upsert. `findById`는 allocations 함께 로드해 `Payment.restore`로 재구성.

- [ ] **Step 1: 실패 통합테스트 작성** — 라운드트립(분할 결제 저장→로드, allocation·status·pgTxId 보존) + PAID 갱신

```java
@SpringBootTest(properties =
    "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@DisplayName("payment 영속 어댑터 통합테스트")
class PaymentPersistenceAdapterIntegrationTest extends AbstractMySqlContainerTest {
    @Autowired PaymentRepository paymentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach void clean() {
        jdbcTemplate.execute("DELETE FROM payment_allocation");
        jdbcTemplate.execute("DELETE FROM payments");
    }

    @Test @DisplayName("분할 결제 저장 후 로드하면 allocation까지 동일하다")
    void roundtrip() {
        Payment p = Payment.create("u1","ord1", Money.won(1_000_000),
            List.of(PaymentAllocation.balance(Money.won(300_000)),
                    PaymentAllocation.external(PaymentMethodType.CARD,"c1",Money.won(700_000))));
        paymentRepository.save(p);

        Payment loaded = paymentRepository.findById(p.id()).orElseThrow();
        assertThat(loaded.totalAmount()).isEqualTo(Money.won(1_000_000));
        assertThat(loaded.allocations()).hasSize(2);
        assertThat(loaded.status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test @DisplayName("PAID 확정 + pg_transaction_id 갱신이 반영된다")
    void update_paid() {
        Payment p = Payment.create("u1","ord1", Money.won(700_000),
            List.of(PaymentAllocation.external(PaymentMethodType.CARD,"c1",Money.won(700_000))));
        paymentRepository.save(p);
        p.allocations().get(0).settle("PG_tx_1");
        p.markPaid(java.time.Instant.parse("2026-05-25T01:00:00Z"));
        paymentRepository.save(p);

        Payment loaded = paymentRepository.findById(p.id()).orElseThrow();
        assertThat(loaded.status()).isEqualTo(PaymentStatus.PAID);
        assertThat(loaded.allocations().get(0).pgTransactionId()).isEqualTo("PG_tx_1");
    }
}
```

- [ ] **Step 2: 실패 확인** — `run-itest` 스킬로 실행(Docker 점검) → FAIL
- [ ] **Step 3: 구현** — 엔티티/리포지토리/어댑터 (balance 패턴). `PaymentJpaEntity`(id,userId,orderId,totalAmount,status(EnumType.STRING),paidAt), `PaymentAllocationJpaEntity`(id,paymentId,methodType,methodId,amount,status,pgTransactionId). 어댑터 `save`는 payment 저장 후 allocations 각각 저장; `findById`는 payment 로드 + `allocationRepo.findByPaymentId` → `Payment.restore`.
- [ ] **Step 4: 통과 확인** — `run-itest` → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add payments/allocation persistence adapter (TDD)"`

---

### Task 11: payment_idempotency 영속 어댑터

**Files (Create):** `adapter/out/persistence/idempotency/` 아래 엔티티/리포지토리/어댑터
**Test:** `PaymentIdempotencyPersistenceAdapterIntegrationTest`

`expired_at`은 NOT NULL — 어댑터가 저장 시 `now + 24h`로 채운다(설계 7-3, 본 스코프는 만료 회수 미구현). `pg_idempotency_key`도 저장.

- [ ] **Step 1: 실패 통합테스트** — start 저장 후 `find(userId,key)` 라운드트립; complete/fail 갱신 반영; 같은 (userId,key) 재저장은 UNIQUE 충돌
- [ ] **Step 2: 실패 확인** (`run-itest`) → FAIL
- [ ] **Step 3: 구현** — 엔티티(idempotencyKey PK, status/pgCallStatus EnumType.STRING, expiredAt). 어댑터 `find`는 `(userId, idempotencyKey)` 조건(보안 격리, 설계 7-3).
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add payment_idempotency persistence adapter (TDD)"`

---

### Task 12: balance_history 영속 어댑터 (balance 컨텍스트)

**Files (Create):** `com/payment/balance/adapter/out/persistence/history/` 엔티티/리포지토리/어댑터
**Test:** `BalanceHistoryPersistenceAdapterIntegrationTest`

`id`는 AUTO_INCREMENT(`@GeneratedValue(IDENTITY)`), append-only.

- [ ] **Step 1: 실패 통합테스트** — `append(BalanceHistory.payment(...))` 후 JdbcTemplate으로 row 조회해 action='PAYMENT', amount_change=-30000, balance_after, payment_id 검증
- [ ] **Step 2: 실패 확인** (`run-itest`) → FAIL
- [ ] **Step 3: 구현** — 엔티티 + `BalanceHistoryRepository.append` 어댑터
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(balance): add balance_history persistence adapter (TDD)"`

---

### Task 13: compensating_transaction_failures 어댑터

**Files (Create):** `adapter/out/persistence/compensation/` 엔티티/리포지토리/어댑터
**Test:** `CompensationFailureAdapterIntegrationTest`

- [ ] **Step 1: 실패 통합테스트** — `record(paymentId, PG_REFUND_CALL, Money.won(700_000))` 후 row 조회로 failure_type/amount/payment_id 검증
- [ ] **Step 2: 실패 확인** (`run-itest`) → FAIL
- [ ] **Step 3: 구현** — 엔티티(id AUTO_INCREMENT, paymentId, failureType, amount nullable). `FailureType` enum 이름을 컬럼 문자열로 저장
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add compensation-failure recorder adapter (TDD)"`

---

### Task 14: `MockPgAdapter`

**Files (Create):** `adapter/out/pg/MockPgAdapter.java`
**Test:** `adapter/out/pg/MockPgAdapterTest.java` (순수 단위 — Spring 불필요)

규칙(테스트 제어용): `methodId`가 `"decline"` 포함 → DECLINED, `"timeout"` 포함 → TIMEOUT, 그 외 APPROVED(`pgTransactionId = "PG_" + UUID`). 설계 6-1: PG는 트랜잭션 밖 호출.

- [ ] **Step 1: 실패 테스트** — 세 경우(approved/declined/timeout) outcome 검증
- [ ] **Step 2: 실패 확인** — Run: `./gradlew :payment:test --tests "*MockPgAdapterTest"` → FAIL
- [ ] **Step 3: 구현**

```java
package com.payment.payment.adapter.out.pg;

import com.payment.payment.application.port.out.PgPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** PG Mock 어댑터(설계 6장). methodId 키워드로 결과를 제어해 결제 시나리오 테스트를 가능케 한다. */
@Component
public class MockPgAdapter implements PgPort {
    @Override
    public PgChargeResult charge(PgChargeRequest req) {
        String id = req.methodId() == null ? "" : req.methodId();
        if (id.contains("decline")) return PgChargeResult.declined("MOCK_DECLINED");
        if (id.contains("timeout")) return PgChargeResult.timeout();
        return PgChargeResult.approved("PG_" + UUID.randomUUID());
    }
}
```

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add MockPgAdapter with keyword-driven outcomes (TDD)"`

---

### Task 15: `KafkaEventPublisher`

**Files (Create):** `adapter/out/kafka/KafkaEventPublisher.java`
**Test:** `adapter/out/kafka/KafkaEventPublisherTest.java` (`KafkaTemplate` mock — 발행 토픽/키/payload 검증, 브로커 불필요)

토픽 `payment-created`, 파티션 키 `userId`(설계 10-2). 직렬화는 `KafkaTemplate<String,Object>` + JSON.

- [ ] **Step 1: 실패 테스트** — `paymentCreated(event)` 시 `kafkaTemplate.send("payment-created", event.userId(), event)` 호출 검증(Mockito)
- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — `@Component`, `KafkaTemplate` 주입, `send(TOPIC, event.userId(), event)`
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add KafkaEventPublisher for payment-created (TDD)"`

> 발행 실패 처리(`kafka_publish_failures`, 설계 10-5)는 로드맵 item 4(배치)에서. 본 Task는 happy path 발행만.

---

### Task 16: `PaymentController` (POST /payments)

**Files (Create):** `adapter/in/web/PaymentController.java`, `PaymentRequest.java`, `PaymentResponse.java`
**Test:** `adapter/in/web/PaymentControllerTest.java` (`@WebMvcTest(PaymentController.class)` + `@MockitoBean ProcessPaymentUseCase`)

`Idempotency-Key` 헤더(필수, 없으면 `MissingRequestHeaderException`→400 via GlobalExceptionHandler). body→`ProcessPaymentCommand` 변환, `PaymentResult`→`PaymentResponse`. 성공 200. `@Valid`로 필드 검증.

- [ ] **Step 1: 실패 테스트 작성**

```java
@WebMvcTest(PaymentController.class)
class PaymentControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @MockitoBean ProcessPaymentUseCase useCase;

    @Test @DisplayName("성공: 200 + payment_id/status")
    void success() throws Exception {
        when(useCase.process(any())).thenReturn(new PaymentResult(
            "PAY1", PaymentStatus.PAID, "u1","ord1", Money.won(1_000_000), Instant.parse("2026-05-25T01:00:00Z"),
            List.of(new PaymentResult.Alloc(PaymentMethodType.BALANCE,null,Money.won(300_000),AllocationStatus.SETTLED,null))));
        String body = """
            {"user_id":"u1","order_id":"ord1","total_amount":1000000,
             "allocations":[{"method_type":"BALANCE","amount":300000},
                            {"method_type":"CARD","method_id":"c1","amount":700000}]}""";
        mvc.perform(post("/payments").header("Idempotency-Key","idem-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.payment_id").value("PAY1"))
            .andExpect(jsonPath("$.status").value("PAID"));
    }

    @Test @DisplayName("Idempotency-Key 헤더 없으면 400")
    void missing_idem_key() throws Exception {
        mvc.perform(post("/payments").contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"u1\",\"order_id\":\"o\",\"total_amount\":1,\"allocations\":[{\"method_type\":\"BALANCE\",\"amount\":1}]}"))
            .andExpect(status().isBadRequest());
    }

    @Test @DisplayName("PG 거절은 400 PG_DECLINED로 매핑")
    void pg_declined_maps_400() throws Exception {
        when(useCase.process(any())).thenThrow(new BusinessException(ErrorCode.PG_DECLINED, "도난카드"));
        String body = """
            {"user_id":"u1","order_id":"ord1","total_amount":1000,"allocations":[{"method_type":"CARD","method_id":"c1","amount":1000}]}""";
        mvc.perform(post("/payments").header("Idempotency-Key","idem-1")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("PG_DECLINED"));
    }
}
```

- [ ] **Step 2: 실패 확인** → FAIL
- [ ] **Step 3: 구현** — JSON 필드는 snake_case(`spring.jackson.property-naming-strategy=SNAKE_CASE`를 `application.yml`에 추가하거나 `@JsonProperty`). 컨트롤러는 헤더+body→command, useCase 호출, result→response(200).

```java
@RestController
@RequiredArgsConstructor
class PaymentController {
    private final ProcessPaymentUseCase useCase;

    @PostMapping("/payments")
    PaymentResponse pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
                        @Valid @RequestBody PaymentRequest req) {
        return PaymentResponse.from(useCase.process(req.toCommand(idempotencyKey)));
    }
}
```
(`PaymentRequest`/`PaymentResponse`는 snake_case 매핑 record + `toCommand`/`from` 변환. `application.yml`에 `spring.jackson.property-naming-strategy: SNAKE_CASE` 추가.)

- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "feat(payment): add PaymentController POST /payments with idempotency header (TDD)"`

---

## Phase D — 트랜잭션 정합성 / 동시성 통합테스트

### Task 17: `PaymentTransactionService` 트랜잭션 통합 + 잔액→한도 락 동시성

**Files:** Test `PaymentTransactionServiceIntegrationTest` (`AbstractMySqlContainerTest`)

실제 MySQL에서: reserve→confirm 후 잔액/누적/payment/idempotency 상태가 일관, 그리고 **같은 사용자 동시 reserve가 잔액 초과를 막는다**(설계 18). balance 통합테스트의 멀티스레드 패턴 재사용.

- [ ] **Step 1: 실패 통합테스트 작성** — seed(잔액 100만, 한도 500만) 후:
  - reserve(80만 결제) 1건 → 잔액 20만, usage 80만
  - 동시 reserve 2건(각 80만) → 1건만 성공, 다른 1건은 `INSUFFICIENT_BALANCE` 또는 락 타임아웃(둘 다 거절로 간주). 최종 잔액 ≥ 0, 정확히 1건만 차감
- [ ] **Step 2: 실패 확인** (`run-itest`) → FAIL (서비스/어댑터 미배선이면 보강)
- [ ] **Step 3: 구현/배선** — 필요한 빈 배선·트랜잭션 전파 확인 (코드 추가 최소)
- [ ] **Step 4: 통과 확인** → PASS
- [ ] **Step 5: Commit** — `git commit -m "test(payment): add tx-integrity and concurrent-payment integration tests"`

---

### Task 18: 결제 happy-path E2E (컨트롤러→DB) + 전체 빌드

**Files:** Test `ProcessPaymentE2ETest` (`@SpringBootTest(webEnvironment=RANDOM_PORT)` + `AbstractMySqlContainerTest`, Kafka auto-config 제외)

- [ ] **Step 1: 실패 E2E 작성** — seed(잔액 100만, 한도 500만) → `POST /payments`(잔액30만+카드70만) → 200 PAID, DB에서 잔액 70만·usage 100만·payments PAID·allocation SETTLED 확인. 같은 Idempotency-Key 재요청 → 200 동일 본문(재처리 없음)
- [ ] **Step 2: 실패 확인** (`run-itest`) → FAIL
- [ ] **Step 3: 구현/배선 보강** → GREEN
- [ ] **Step 4: 전체 빌드** — Run: `cd 01_src && ./gradlew build` → BUILD SUCCESSFUL
- [ ] **Step 5: Commit** — `git commit -m "test(payment): add happy-path E2E and idempotent replay (TDD)"`

---

## 마무리

- [ ] `kent-beck-review` 스킬로 전체 변경 리뷰 (단순설계 4규칙·Tidy First)
- [ ] 로드맵 `02_doc/planning/implementation-roadmap.md`의 "1. payment 컨텍스트" 완료 처리 + balance_history 메모 반영
- [ ] `feat/payment-core` 푸시 → GitHub PR (CLAUDE.md 원칙, 사용자 확인 후)

---

## 자기검토 메모 (작성자 확인)

- **설계 커버리지**: 시나리오 11~20 + 22/23(내부 원복+PG회수 보류) 커버. 시나리오 21(보정 배치 분기)·24(Kafka 실패 재발행)·25~30(충전)은 로드맵 item 2(charge)·item 4(배치) 소관 — 본 계획 범위 밖(의도적).
- **타입 일관성**: `Charged`(allocationId,pgTransactionId), `Reservation`(paymentId,idempotencyKey,pgIdempotencyKey,externalAllocations), `PgChargeResult.Outcome{APPROVED,DECLINED,TIMEOUT}`, `CompensationFailureRecorder.FailureType{BALANCE_ROLLBACK,USAGE_ROLLBACK,PG_REFUND_CALL}` — Task 6/7/8/9에서 동일 사용.
- **미해결 가정**: ① 한도 누적 단위 = total_amount 전액(설계 0장/19 재확인 필요 시 reserve의 `usage.use(cmd.totalAmount())`만 변경). ② `user_daily_limit` row가 없으면 INVALID_REQUEST(시드 전제) — 정책 기본값은 본 스코프 밖. ③ idempotency `expired_at`=now+24h 하드코딩.
