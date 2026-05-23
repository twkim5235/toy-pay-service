package com.payment.common.error;

/**
 * 도메인/애플리케이션 에러 코드. HTTP 상태는 plain int로 보관해 도메인 계층이 Spring에 의존하지 않게 한다
 * (설계 8장 HTTP 상태 코드 표 기준).
 */
public enum ErrorCode {

    // 400
    INVALID_REQUEST(400, "요청이 올바르지 않습니다"),
    MISSING_IDEMPOTENCY_KEY(400, "Idempotency-Key 헤더가 필요합니다"),
    INSUFFICIENT_BALANCE(400, "잔액이 부족합니다"),
    DAILY_LIMIT_EXCEEDED(400, "일일 결제 한도를 초과했습니다"),
    PG_DECLINED(400, "PG사가 결제를 거절했습니다"),

    // 409
    PAYMENT_IN_PROGRESS(409, "동일 요청이 처리 중입니다"),
    CHARGE_IN_PROGRESS(409, "동일 충전 요청이 처리 중입니다"),

    // 500 / 503
    INTERNAL_ERROR(500, "내부 오류가 발생했습니다"),
    INCONSISTENT_STATE(500, "정합성이 깨진 상태입니다. 잠시 후 다시 시도해 주세요"),
    PG_UNAVAILABLE(503, "PG 서비스를 일시적으로 사용할 수 없습니다");

    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
