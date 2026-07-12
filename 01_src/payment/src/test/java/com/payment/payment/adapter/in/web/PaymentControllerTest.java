package com.payment.payment.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.payment.common.Money;
import com.payment.common.error.BusinessException;
import com.payment.common.error.ErrorCode;
import com.payment.payment.application.port.in.PaymentResult;
import com.payment.payment.application.port.in.ProcessPaymentCommand;
import com.payment.payment.application.port.in.ProcessPaymentUseCase;
import com.payment.payment.domain.AllocationStatus;
import com.payment.payment.domain.PaymentMethodType;
import com.payment.payment.domain.PaymentStatus;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
@DisplayName("POST /payments 컨트롤러 (설계 6-1)")
class PaymentControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ProcessPaymentUseCase useCase;

    private static final String SPLIT_BODY = """
            {"user_id":"u1","order_id":"ord1","total_amount":1000000,
             "allocations":[{"method_type":"BALANCE","amount":300000},
                            {"method_type":"CARD","method_id":"c1","amount":700000}]}""";

    private PaymentResult paidResult() {
        return new PaymentResult("PAY1", PaymentStatus.PAID, "u1", "ord1",
                Money.won(1_000_000), Instant.parse("2026-07-08T00:00:00Z"),
                List.of(new PaymentResult.Alloc(PaymentMethodType.BALANCE, null,
                                Money.won(300_000), AllocationStatus.SETTLED, null),
                        new PaymentResult.Alloc(PaymentMethodType.CARD, "c1",
                                Money.won(700_000), AllocationStatus.SETTLED, "PG_tx_1")));
    }

    @Test
    @DisplayName("성공: 200 + snake_case 응답 본문(payment_id/status/allocations/pg_transaction_id)")
    void successReturns200WithBody() throws Exception {
        when(useCase.process(any())).thenReturn(paidResult());

        mvc.perform(post("/payments").header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(SPLIT_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment_id").value("PAY1"))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.user_id").value("u1"))
                .andExpect(jsonPath("$.order_id").value("ord1"))
                .andExpect(jsonPath("$.total_amount").value(1_000_000))
                .andExpect(jsonPath("$.paid_at").value("2026-07-08T00:00:00Z"))
                .andExpect(jsonPath("$.allocations[0].method_type").value("BALANCE"))
                .andExpect(jsonPath("$.allocations[0].amount").value(300_000))
                .andExpect(jsonPath("$.allocations[1].method_id").value("c1"))
                .andExpect(jsonPath("$.allocations[1].pg_transaction_id").value("PG_tx_1"));
    }

    @Test
    @DisplayName("요청 변환: 헤더의 Idempotency-Key와 body가 ProcessPaymentCommand로 매핑된다")
    void mapsHeaderAndBodyToCommand() throws Exception {
        when(useCase.process(any())).thenReturn(paidResult());

        mvc.perform(post("/payments").header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(SPLIT_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<ProcessPaymentCommand> command = ArgumentCaptor.forClass(ProcessPaymentCommand.class);
        verify(useCase).process(command.capture());
        assertThat(command.getValue().idempotencyKey()).isEqualTo("idem-1");
        assertThat(command.getValue().userId()).isEqualTo("u1");
        assertThat(command.getValue().orderId()).isEqualTo("ord1");
        assertThat(command.getValue().totalAmount()).isEqualTo(Money.won(1_000_000));
        assertThat(command.getValue().allocations()).containsExactly(
                new ProcessPaymentCommand.Line(PaymentMethodType.BALANCE, null, Money.won(300_000)),
                new ProcessPaymentCommand.Line(PaymentMethodType.CARD, "c1", Money.won(700_000)));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더 없으면 400 MISSING_IDEMPOTENCY_KEY, 유스케이스 미호출")
    void missingIdempotencyKeyHeader() throws Exception {
        mvc.perform(post("/payments")
                        .contentType(MediaType.APPLICATION_JSON).content(SPLIT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        verify(useCase, never()).process(any());
    }

    @Test
    @DisplayName("형식 위반(user_id 공백): 400 INVALID_REQUEST, 유스케이스 미호출")
    void blankUserIdRejected() throws Exception {
        String body = """
                {"user_id":"","order_id":"ord1","total_amount":1000,
                 "allocations":[{"method_type":"BALANCE","amount":1000}]}""";

        mvc.perform(post("/payments").header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verify(useCase, never()).process(any());
    }

    @Test
    @DisplayName("PG 거절: 400 + code=PG_DECLINED로 매핑된다")
    void pgDeclinedMapsTo400() throws Exception {
        when(useCase.process(any())).thenThrow(new BusinessException(ErrorCode.PG_DECLINED, "도난카드"));

        mvc.perform(post("/payments").header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(SPLIT_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PG_DECLINED"))
                .andExpect(jsonPath("$.message").value("도난카드"));
    }

    @Test
    @DisplayName("멱등 진행중: 409 + code=PAYMENT_IN_PROGRESS로 매핑된다")
    void inProgressMapsTo409() throws Exception {
        when(useCase.process(any())).thenThrow(new BusinessException(ErrorCode.PAYMENT_IN_PROGRESS));

        mvc.perform(post("/payments").header("Idempotency-Key", "idem-1")
                        .contentType(MediaType.APPLICATION_JSON).content(SPLIT_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
    }
}
