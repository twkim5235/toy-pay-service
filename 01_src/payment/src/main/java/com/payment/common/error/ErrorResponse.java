package com.payment.common.error;

/** 표준 에러 응답 본문. code는 클라이언트 분기용, message는 사람용. */
public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}
