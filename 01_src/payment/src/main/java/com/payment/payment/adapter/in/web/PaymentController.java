package com.payment.payment.adapter.in.web;

import com.payment.payment.application.port.in.ProcessPaymentUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제 in 어댑터 (설계 6-1 POST /payments). 헤더+본문을 커맨드로 변환해 유스케이스에 위임할 뿐,
 * 규칙은 갖지 않는다. 에러 → HTTP 매핑은 {@code GlobalExceptionHandler}.
 */
@RestController
@RequiredArgsConstructor
class PaymentController {

    private final ProcessPaymentUseCase useCase;

    @PostMapping("/payments")
    PaymentResponse pay(@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        return PaymentResponse.from(useCase.process(request.toCommand(idempotencyKey)));
    }
}
