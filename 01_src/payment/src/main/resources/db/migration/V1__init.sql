-- ============================================================
--  payment 서버 스키마  (스펙 단일 소유: 02_doc/spec/payment-core-spec.md 3장)
--  컨벤션: FK 미설정, VARCHAR(64) UUID PK, BIGINT 금액(원 단위),
--          감사 로그는 INSERT-only
-- ============================================================

-- ----- 결제 본체 ------------------------------------------------
-- payments (cancel 문서 기반 + v0_2: user_id 추가, status 확장)
CREATE TABLE payments (
    id              VARCHAR(64)  NOT NULL,
    user_id         VARCHAR(64)  NOT NULL,
    order_id        VARCHAR(64)  NOT NULL,
    total_amount    BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL, -- PENDING/PAID/FAILED/FAILED_REFUNDED/PARTIALLY_CANCELLED/CANCELLED
    paid_at         TIMESTAMP    NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_order_id (order_id),
    INDEX idx_user_id (user_id)
);

-- payment_items (merchant 단위 분해 — 취소 도메인에서 사용. 본 스코프는 스키마만 보유)
CREATE TABLE payment_items (
    id              VARCHAR(64)  NOT NULL,
    payment_id      VARCHAR(64)  NOT NULL,
    merchant_id     VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    status          VARCHAR(32)  NOT NULL, -- PENDING/PAID/CANCELLED
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_payment_id (payment_id),
    INDEX idx_merchant_id (merchant_id)
);

-- payment_allocation (분할 결제의 핵심: 결제수단별 분배)
CREATE TABLE payment_allocation (
    id                  VARCHAR(64)  NOT NULL,
    payment_id          VARCHAR(64)  NOT NULL,
    method_type         VARCHAR(32)  NOT NULL, -- BALANCE/CARD/ACCOUNT
    method_id           VARCHAR(64)  NULL,     -- BALANCE는 NULL
    amount              BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL, -- PENDING/SETTLED/FAILED/REFUNDED
    pg_transaction_id   VARCHAR(128) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_payment_id (payment_id),
    INDEX idx_method_id (method_id),
    INDEX idx_status_created (status, created_at)
);

-- charges (충전 기록 — 결제와 별도 도메인)
CREATE TABLE charges (
    id                  VARCHAR(64)  NOT NULL,
    user_id             VARCHAR(64)  NOT NULL,
    method_type         VARCHAR(32)  NOT NULL, -- CARD/ACCOUNT
    method_id           VARCHAR(64)  NOT NULL,
    amount              BIGINT       NOT NULL,
    status              VARCHAR(32)  NOT NULL, -- PENDING/COMPLETED/FAILED
    pg_transaction_id   VARCHAR(128) NULL,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_status_created (status, created_at)
);

-- ----- 사용자 자원 (잔액·한도) ----------------------------------
-- user_balance (사용자 잔액, row 1개 = 사용자, 동시성 락 단위)
CREATE TABLE user_balance (
    user_id         VARCHAR(64)  NOT NULL,
    balance         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
);

-- balance_history (잔액 변경 감사 로그, INSERT-only, 법적 보존)
CREATE TABLE balance_history (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    user_id         VARCHAR(64)  NOT NULL,
    action          VARCHAR(16)  NOT NULL, -- CHARGE/PAYMENT/ROLLBACK (REFUND은 [2차] 환불 도메인)
    amount_change   BIGINT       NOT NULL, -- 부호 포함 (+충전 / -결제)
    balance_after   BIGINT       NOT NULL,
    payment_id      VARCHAR(64)  NULL,
    charge_id       VARCHAR(64)  NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_user_created (user_id, created_at),
    INDEX idx_payment_id (payment_id),
    INDEX idx_charge_id (charge_id)
);

-- user_daily_usage (사용자 일일 결제 누적, 사기 방지 한도)
CREATE TABLE user_daily_usage (
    user_id         VARCHAR(64)  NOT NULL,
    used_amount     BIGINT       NOT NULL DEFAULT 0,
    last_reset_date DATE         NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
);

-- user_daily_limit (사용자 일일 한도 설정값)
CREATE TABLE user_daily_limit (
    user_id         VARCHAR(64)  NOT NULL,
    daily_limit     BIGINT       NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id)
);

-- ----- 멱등성 --------------------------------------------------
-- payment_idempotency (결제 멱등성 + PG 호출 추적)
CREATE TABLE payment_idempotency (
    idempotency_key      VARCHAR(64)  NOT NULL,
    user_id              VARCHAR(64)  NOT NULL,
    order_id             VARCHAR(64)  NOT NULL,
    payment_id           VARCHAR(64)  NULL,
    status               VARCHAR(32)  NOT NULL, -- PENDING/COMPLETED/FAILED
    pg_call_status       VARCHAR(32)  NOT NULL DEFAULT 'NOT_CALLED', -- NOT_CALLED/CALLING/SUCCESS/FAILED/UNKNOWN
    pg_idempotency_key   VARCHAR(128) NULL,
    retry_count          INT          NOT NULL DEFAULT 0,
    expired_at           TIMESTAMP    NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (idempotency_key),
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    INDEX idx_order_id (order_id),
    INDEX idx_payment_id (payment_id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_pg_call_status_created (pg_call_status, created_at)
);

-- charge_idempotency (충전 멱등성)
CREATE TABLE charge_idempotency (
    idempotency_key      VARCHAR(64)  NOT NULL,
    user_id              VARCHAR(64)  NOT NULL,
    charge_id            VARCHAR(64)  NULL,
    status               VARCHAR(32)  NOT NULL,
    pg_call_status       VARCHAR(32)  NOT NULL DEFAULT 'NOT_CALLED',
    pg_idempotency_key   VARCHAR(128) NULL,
    retry_count          INT          NOT NULL DEFAULT 0,
    expired_at           TIMESTAMP    NOT NULL,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (idempotency_key),
    UNIQUE KEY uk_user_idempotency (user_id, idempotency_key),
    INDEX idx_charge_id (charge_id),
    INDEX idx_status_created (status, created_at),
    INDEX idx_pg_call_status_created (pg_call_status, created_at)
);

-- ----- 보조 (실패 추적) ----------------------------------------
-- compensating_transaction_failures (보상 트랜잭션 실패 — 수동 복구 채널)
CREATE TABLE compensating_transaction_failures (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    payment_id      VARCHAR(64)  NULL,
    charge_id       VARCHAR(64)  NULL,
    failure_type    VARCHAR(32)  NOT NULL, -- BALANCE_ROLLBACK/USAGE_ROLLBACK/PG_REFUND_CALL/REFUND_LIMIT_ROLLBACK
    amount          BIGINT       NULL,
    failed_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_payment_id (payment_id),
    INDEX idx_charge_id (charge_id),
    INDEX idx_failed_at (failed_at)
);

-- kafka_publish_failures (Kafka 발행 실패 — 자동 재발행 채널)
CREATE TABLE kafka_publish_failures (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    topic           VARCHAR(128) NOT NULL,
    message_key     VARCHAR(128) NULL,
    payload         JSON         NOT NULL,
    retry_count     INT          NOT NULL DEFAULT 0,
    failed_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_failed_at (failed_at)
);

-- ----- ShedLock (보정 배치 분산 단일 실행) -----------------------
CREATE TABLE shedlock (
    name        VARCHAR(64)  NOT NULL,
    lock_until  TIMESTAMP(3) NOT NULL,
    locked_at   TIMESTAMP(3) NOT NULL,
    locked_by   VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
