---
name: tdd-cycle
description: 이 프로젝트(간편결제, 헥사고날+DDD)에서 도메인/애플리케이션/어댑터 로직을 TDD로 구현할 때 사용. 실패 테스트(Red) → 최소 구현(Green) → 리팩터 사이클을 프로젝트 컨벤션대로 진행한다. "TDD로 ~ 구현", "테스트 먼저" 요청 시.
---

# TDD 사이클 (이 프로젝트 규약)

## 원칙
- **Red → Green → Refactor.** 실패하는 테스트 먼저 → 실행해서 빨간불 확인 → 최소 구현으로 통과 → 리팩터.
- 테스트 없이 프로덕션 코드를 먼저 쓰지 않는다.
- 커밋은 Red→Green 한 사이클이 후보. **단, 자동 커밋 금지 — 항상 사용자 확인 후 커밋** (스테이징 + 메시지 초안만 보여주고 대기).

## 레이어별 테스트 전략
- **도메인 (`com.payment.<ctx>.domain`)** — 순수 Java 단위테스트. Spring/DB/Mockito 없음.
  - 시간 의존 로직은 `today`(LocalDate) 등을 **파라미터로 주입** — 시스템 시계 의존 금지 (예: `UserDailyUsage`의 자정 lazy 리셋).
  - 금액은 `Money` 값객체. 규칙 위반은 `throw new BusinessException(ErrorCode.XXX)`.
- **애플리케이션 서비스 (`application`)** — out 포트를 **Mockito 목**으로. 트랜잭션 경계·오케스트레이션 검증.
- **어댑터 (`adapter/out/persistence`)** — Testcontainers MySQL 통합테스트 (`/run-itest`). 실제 락·스키마 정합성 검증.

## 테스트 작성 컨벤션
- JUnit5 + AssertJ (`assertThat`, `assertThatThrownBy`).
- `@DisplayName`과 테스트 메서드명 **한글**. 예: `@DisplayName("잔액보다 큰 금액을 차감하면 INSUFFICIENT_BALANCE 예외")`.
- 예외 + 에러코드 검증:
  ```java
  assertThatThrownBy(() -> balance.deduct(Money.won(10_001)))
      .isInstanceOfSatisfying(BusinessException.class,
          e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_BALANCE));
  ```
- 경계값(0, 한도와 동일, 직전/직후) 케이스를 반드시 포함.

## 사이클 실행
1. 실패 테스트 작성 → 빨간불 확인:
   `cd 01_src && ./gradlew :payment:test --tests "com.payment.<ctx>.<Class>Test"`
2. 최소 구현 → 초록불 확인 (같은 명령).
3. 리팩터 (테스트 그린 유지).
4. (선택) 전체 회귀: `./gradlew :payment:test`
5. 그린이면 변경 스테이징 + 커밋 메시지 초안 제시 → **사용자 승인 후** 커밋. 메시지 예: `feat(<ctx>): ... (TDD)`.

## 함께 지킬 규약
- 의존 규칙: adapter → application → domain. 도메인은 인프라(Spring/DB/Kafka/Lombok)를 모른다.
- 포트 메서드는 의도로 명명 (예: `findByUserIdWithPessimisticLock`; SQL 용어 `ForUpdate` 금지).