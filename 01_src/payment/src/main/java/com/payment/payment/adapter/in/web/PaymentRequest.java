package com.payment.payment.adapter.in.web;

import com.payment.common.Money;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.domain.PaymentMethodType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * POST /payments 요청 본문 (설계 6-1). 형식 검증만 담당한다 —
 * 분배 합계·BALANCE 개수·method_id 중복(INV-4/5/6)은 도메인 {@code Payment}가 강제한다.
 */
record PaymentRequest(
        @NotBlank String userId,
        @NotBlank String orderId,
        @Positive long totalAmount,
        @NotEmpty @Valid List<AllocationLine> allocations) {

    record AllocationLine(
            @NotNull PaymentMethodType methodType,
            String methodId,
            @Positive long amount) {}

    ProcessPaymentCommand toCommand(String idempotencyKey) {
        return new ProcessPaymentCommand(idempotencyKey, userId, orderId, Money.won(totalAmount),
                allocations.stream()
                        .map(line -> new ProcessPaymentCommand.Line(
                                line.methodType(), line.methodId(), Money.won(line.amount())))
                        .toList());
    }
}
