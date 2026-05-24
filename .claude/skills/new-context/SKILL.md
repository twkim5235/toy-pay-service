---
name: new-context
description: payment 모듈에 새 바운디드 컨텍스트(예: payment, charge)를 헥사고날+DDD 골격으로 스캐폴딩할 때 사용. 패키지 구조·의존규칙·package-private·Lombok 범위 규약을 적용한다. "새 컨텍스트", "<X> 컨텍스트 만들자" 요청 시.
---

# 새 바운디드 컨텍스트 스캐폴딩

패키지 루트: `com.payment.<context>` (context = payment / charge / balance / common 같은 바운디드 컨텍스트).

## 패키지 골격
```
com.payment.<context>/
├─ domain/                          # 순수 Java. 비즈니스 규칙·값객체·애그리거트.
│                                   # Spring/JPA/Lombok 애너테이션 절대 없음.
├─ application/
│  ├─ port/in/                      # 유스케이스 인터페이스 (driving)
│  ├─ port/out/                     # 리포지토리/외부 포트 (driven). 의도로 명명.
│  └─ <UseCase>Service.java         # @Service, 트랜잭션 경계, 포트 오케스트레이션
└─ adapter/
   ├─ in/web/                       # @RestController
   ├─ out/persistence/<aggregate>/  # 애그리거트별 하위 패키지
   │     ├─ <X>JpaEntity.java        # @Entity, package-private
   │     ├─ <X>JpaRepository.java    # Spring Data, package-private
   │     └─ <X>PersistenceAdapter.java # implements port, @Repository, package-private
   ├─ out/pg/                       # PgPort 구현 (Mock 어댑터)
   └─ out/kafka/                    # 이벤트 발행
```

## 규약 (필수)
- **의존 규칙: adapter → application → domain.** 도메인은 Spring/DB/Kafka를 모른다.
- **포트는 `application/port/out`에**, 의도로 명명 (`findByUserIdWithPessimisticLock` O / `...ForUpdate` X — SQL 키워드 누출 금지).
- **JPA 엔티티/리포지토리/어댑터는 package-private** → JPA 엔티티가 domain/application으로 누출 방지. **애그리거트별 하위 패키지**로 묶어 캡슐화 유지.
- **엔티티 ↔ 도메인 분리.** 엔티티에 `static from(domain)` / `toDomain()` 매핑. `created_at`/`updated_at`은 DB가 관리(매핑 생략).
- **Lombok은 adapter/application 레이어만** (`@RequiredArgsConstructor`). 도메인은 순수 Java.
- **비관적 락**: Spring Data에 `@Lock(LockModeType.PESSIMISTIC_WRITE)` + `@Query`. 두 row 이상 락은 **고정 순서**(예: 잔액→한도)로 잡아 데드락 회피 (설계 9-2). 락 보유 시간 최소화 — 외부(PG) 호출은 트랜잭션 밖.
- 스키마는 **Flyway 단독 소유**, `ddl-auto: validate`. 엔티티는 `db/migration/V1__init.sql` 스키마에 맞춘다.

## 절차
1. 설계 문서(`02_doc/design/`)에서 해당 컨텍스트의 도메인 규칙·테이블·시나리오 확인.
2. 도메인부터 TDD (`/tdd-cycle`).
3. out 포트 → JPA 어댑터 → Testcontainers 통합테스트 (`/run-itest`).
4. 컨텍스트(기능) 단위로 브랜치 → 커밋(확인 후) → PR.
