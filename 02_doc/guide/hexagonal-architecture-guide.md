# 헥사고날 아키텍처 가이드 (이 프로젝트 기준)

> 이 문서는 우리 payment 프로젝트의 **실제 코드**를 예시로 헥사고날 아키텍처(포트&어댑터)를 설명한다.
> 개념만 보면 헷갈리니, `com.payment.payment` 컨텍스트에 이미 있는 클래스로 하나씩 짚는다.

---

## 1. 한 줄 요약

> **"바깥세상(웹·DB·PG·Kafka)을 안쪽 비즈니스 로직에서 떼어내고, 그 사이를 '인터페이스(포트)'로만 연결한다."**

- 안쪽(도메인·유스케이스)은 바깥(Spring·MySQL·Kafka)을 **모른다**.
- 바깥이 안쪽 인터페이스에 **맞춰 꽂힌다**(그래서 "어댑터").
- 덕분에 DB를 MySQL→다른 걸로 바꿔도, PG를 Mock→실제로 바꿔도 **안쪽 코드는 안 바뀐다.**

---

## 2. 왜 이렇게 하나 (문제의식)

계층 없이 컨트롤러에서 바로 JPA·PG SDK를 호출하면:
- 비즈니스 규칙이 DB/HTTP 코드에 뒤섞여 **테스트가 느리고 어렵다**(항상 DB·네트워크 필요).
- 인프라를 바꾸면 비즈니스 코드까지 줄줄이 바뀐다.

헥사고날은 **의존성 방향을 한쪽으로 고정**해서 이걸 막는다:

```
adapter  ──→  application  ──→  domain
(바깥)          (유스케이스)        (핵심 규칙)

의존은 항상 안쪽으로만. 도메인은 아무도 의존하지 않고, 아무것도 의존하지 않는다.
```

이 규칙은 CLAUDE.md에도 못박혀 있다:
> **의존 규칙: adapter → application → domain. 도메인은 인프라(Spring/DB/Kafka)를 모른다.**

---

## 3. 세 개의 원 (레이어)

### ① domain — 핵심 규칙 (가장 안쪽)
순수 Java. Spring도, JPA도, Lombok도 없다. "결제란 무엇이고 어떤 규칙을 지켜야 하는가"만 담는다.

- 예: `com.payment.payment.domain.Payment`
  - "분배 금액의 합 == total_amount", "BALANCE 수단은 최대 1개" 같은 **불변식**을 생성자에서 강제한다.
  - `markPaid()`, `markFailed()` 같은 상태 전이만 표현한다. PG를 호출하거나 DB에 저장하는 방법은 **모른다.**
- 값 객체: `com.payment.common.Money` (원 단위 정수, 음수 방지).

> 판별법: 이 클래스에 `import org.springframework...`나 `jakarta.persistence...`가 있으면 도메인이 아니다.

### ② application — 유스케이스 (트랜잭션 경계)
"결제를 처리한다"는 **시나리오의 흐름**을 조율한다. 하지만 실제 DB·PG 구현은 모른다 — **포트(인터페이스)에게 시킨다.**

- 예: `com.payment.payment.application.PaymentTransactionService`
  - 잔액 차감 → 한도 차감 → 결제 저장… 흐름을 조율.
  - 저장은 `PaymentRepository`(인터페이스)에게, PG 호출은 `PgPort`(인터페이스)에게 위임.
- 여기서 `@Transactional` 트랜잭션 경계가 그어진다.

### ③ adapter — 바깥세상 연결 (가장 바깥)
포트 인터페이스를 **실제 기술로 구현**하거나(out), 외부 요청을 유스케이스로 **넘겨준다**(in).

- 예: `com.payment.payment.adapter.out.persistence.payment.PaymentPersistenceAdapter`
  - `PaymentRepository` 포트를 **JPA로 구현**한 것. 여기엔 `@Repository`, JPA 엔티티가 있다.

---

## 4. 포트(Port)와 어댑터(Adapter) — 핵심 개념

**포트 = 구멍(인터페이스). 어댑터 = 그 구멍에 꽂는 플러그(구현체).**

방향에 따라 두 종류:

### 인바운드(in) 포트 — "바깥이 우리를 부르는 입구"
- 포트: `application/port/in/ProcessPaymentUseCase` (인터페이스)
- 어댑터: `adapter/in/web/PaymentController` (HTTP 요청을 받아 유스케이스 호출) — *아직 미구현, 다음 작업*

```
[HTTP 요청] → PaymentController(in 어댑터) → ProcessPaymentUseCase(in 포트) → 유스케이스 실행
```

### 아웃바운드(out) 포트 — "우리가 바깥에 나가는 출구"
- 포트: `application/port/out/PaymentRepository` (인터페이스, 유스케이스가 "저장해줘"라고 요청)
- 어댑터: `adapter/out/persistence/payment/PaymentPersistenceAdapter` (JPA로 실제 저장)

```
유스케이스 → PaymentRepository(out 포트) → PaymentPersistenceAdapter(out 어댑터) → MySQL
```

**의존 역전이 여기서 일어난다:** 유스케이스는 `PaymentPersistenceAdapter`(구현)를 모른다. 오직 `PaymentRepository`(인터페이스)만 안다. Spring이 런타임에 구현체를 꽂아준다(DI). 그래서 화살표가 `adapter → application`으로, 안쪽을 향한다.

---

## 5. 우리 프로젝트 패키지 구조

`com.payment.<컨텍스트>` (컨텍스트 = payment / charge / balance / common), 각 컨텍스트 안:

```
com.payment.payment/
├── domain/                         # ① 순수 Java 규칙
│   ├── Payment.java                #   애그리거트 루트 (분할결제 불변식)
│   ├── PaymentAllocation.java      #   결제수단별 분배
│   └── PaymentIdempotency.java     #   멱등성 상태
│
├── application/                    # ② 유스케이스 + 포트 정의
│   ├── PaymentTransactionService.java   # 트랜잭션 경계 조율
│   └── port/
│       ├── in/                     #   인바운드 포트 (우리를 부르는 입구)
│       │   └── ProcessPaymentUseCase.java
│       └── out/                    #   아웃바운드 포트 (우리가 나가는 출구)
│           ├── PaymentRepository.java
│           ├── PaymentIdempotencyRepository.java
│           ├── PgPort.java
│           └── PaymentEventPublisher.java
│
└── adapter/                        # ③ 바깥세상 구현
    ├── in/web/                     #   PaymentController (미구현)
    └── out/
        ├── persistence/            #   JPA 어댑터 (구현 완료)
        │   ├── idempotency/PaymentIdempotencyPersistenceAdapter
        │   └── payment/PaymentPersistenceAdapter
        ├── pg/                     #   MockPgAdapter (미구현)
        └── kafka/                  #   KafkaEventPublisher (미구현)
```

**핵심: 포트(인터페이스)는 `application`이 소유하고, 어댑터(구현)는 `adapter`가 소유한다.** 유스케이스가 "내가 필요한 계약"을 out 포트로 선언하면, 어댑터가 그 계약을 이행한다.

---

## 6. 실제 예시: out 포트 하나가 사는 법

우리가 방금 TDD로 만든 `PaymentIdempotencyRepository`를 따라가 보자.

**(1) 유스케이스가 필요한 계약을 포트로 선언** — `application/port/out/PaymentIdempotencyRepository.java`
```java
public interface PaymentIdempotencyRepository {
    Optional<PaymentIdempotency> find(String userId, String idempotencyKey);
    void save(PaymentIdempotency idempotency);
}
```
→ "나는 멱등성을 (userId, key)로 찾고 저장할 수 있어야 해." **DB가 MySQL인지 몰라도 된다.**

**(2) 어댑터가 JPA로 그 계약을 구현** — `adapter/out/persistence/idempotency/PaymentIdempotencyPersistenceAdapter.java`
```java
@Repository
@RequiredArgsConstructor
class PaymentIdempotencyPersistenceAdapter implements PaymentIdempotencyRepository {
    private final PaymentIdempotencyJpaRepository jpaRepository;

    @Override public Optional<PaymentIdempotency> find(String userId, String key) {
        return jpaRepository.findByUserIdAndIdempotencyKey(userId, key)
                .map(PaymentIdempotencyJpaEntity::toDomain);   // JPA 엔티티 → 도메인 변환
    }
    @Override public void save(PaymentIdempotency idem) {
        jpaRepository.save(PaymentIdempotencyJpaEntity.from(idem));  // 도메인 → JPA 엔티티 변환
    }
}
```
→ 여기서만 JPA(`@Entity`, `jakarta.persistence`)를 안다. **도메인 객체(`PaymentIdempotency`)와 JPA 엔티티(`...JpaEntity`)를 서로 변환**(`from`/`toDomain`)하는 게 어댑터의 일이다.

**왜 굳이 엔티티를 따로 두나?** 도메인을 순수하게 지키려고. `PaymentIdempotency`(도메인)에 `@Entity`를 붙이면 도메인이 JPA에 오염된다. 그래서 매핑 전용 `PaymentIdempotencyJpaEntity`를 어댑터 안에 따로 두고 변환한다.

---

## 7. 테스트가 왜 쉬워지나 (이게 핵심 이득)

포트가 인터페이스라서, 레이어마다 **딱 맞는 테스트**를 쓸 수 있다:

| 레이어 | 테스트 방식 | 이 프로젝트 예 |
|--------|-------------|----------------|
| domain | 순수 단위테스트 (Spring/DB 없음, 빠름) | `PaymentTest`, `MoneyTest` |
| application | out 포트를 **Mockito 목**으로 대체 | `PaymentTransactionService` 테스트 |
| adapter (out) | Testcontainers 실제 MySQL | `PaymentIdempotencyPersistenceAdapterIntegrationTest` |

- 유스케이스를 테스트할 땐 진짜 DB 대신 `PaymentRepository`를 **가짜(mock)**로 꽂는다 → 빠르고, 흐름만 검증.
- 어댑터를 테스트할 땐 **실제 MySQL**(Testcontainers)로 SQL·매핑 정합성만 검증(라운드트립).

포트가 없으면 이 분리가 불가능하다 — 모든 테스트가 DB를 켜야 한다.

---

## 8. 자주 헷갈리는 지점 정리

- **"포트는 어느 레이어?"** → `application`. 유스케이스가 자기 필요를 선언하는 것이라 application이 소유한다. (도메인이 아니다.)
- **"in 포트 vs out 포트?"** → 화살표가 **들어오면 in**(웹→유스케이스), **나가면 out**(유스케이스→DB/PG).
- **"어댑터가 왜 안쪽을 의존?"** → 어댑터가 application의 인터페이스를 `implements` 하니까. 구현이 계약에 의존하는 것 = 의존 역전(DIP).
- **"도메인에 Lombok 되나?"** → 안 된다. 도메인은 순수 Java. Lombok은 adapter/application의 DI 보일러플레이트(`@RequiredArgsConstructor`)에만.
- **"엔티티 = 도메인?"** → 아니다. `Payment`(도메인)와 `PaymentJpaEntity`(JPA 매핑)는 별개. 어댑터에서 변환한다.

---

## 9. 이 프로젝트만의 결정 (참고)

- 잔액·한도는 별도 서버가 아니라 payment **내부 모듈** → 단일 DB 트랜잭션 원자성.
- PG는 `PgPort` 인터페이스 + Mock 어댑터, **트랜잭션 밖**에서 호출.
- 결제 흐름 전체 조율(오케스트레이터)은 `ProcessPaymentOrchestrator`(예정)가 맡고 `@Transactional`을 걸지 않는다(PG 호출이 트랜잭션 밖이어야 하므로).

자세한 근거는 `02_doc/design/payment-design-v0_2.md`, `02_doc/design/payment-core-decisions.md` 참고.
