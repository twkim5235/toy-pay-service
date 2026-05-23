package com.payment.common.web;

import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.common.error.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 예외를 설계 8장 HTTP 상태 코드/에러 코드로 변환하는 전역 핸들러. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.errorCode();
        if (code.httpStatus() >= 500) {
            log.error("business error(5xx) code={} msg={}", code, e.getMessage(), e);
        } else {
            log.warn("business error code={} msg={}", code, e.getMessage());
        }
        return ResponseEntity.status(code.httpStatus())
                .body(ErrorResponse.of(code, e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingRequestHeaderException.class})
    public ResponseEntity<ErrorResponse> handleValidation(Exception e) {
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.httpStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unexpected error", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage()));
    }
}
