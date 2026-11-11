package idu.sba.backend.domain.payment.client;

import idu.sba.backend.domain.payment.dto.PaymentMethodRequestDTO;
import idu.sba.backend.domain.payment.dto.TossBillingKeyResponseDTO;
import idu.sba.backend.domain.payment.dto.TossPaymentApproveResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class TossPaymentClient {

    private final RestClient tossRestClient;  // secretKey, authHeader() 다 필요 없어짐

    // SDK 결제창 방식: 프론트가 받은 authKey로 빌링키를 발급받는다.
    // (키인 방식의 /authorizations/card + 원본 카드정보 전송을 대체)
    public TossBillingKeyResponseDTO issueBillingKey(PaymentMethodRequestDTO dto) {
        return tossRestClient.post()
                .uri("/v1/billing/authorizations/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "authKey", dto.authKey(),
                        "customerKey", dto.customerKey()))
                .retrieve()
                .body(TossBillingKeyResponseDTO.class);
    }


    // 빌링키 → 실제 청구 (매달 스케줄러)
    public TossPaymentApproveResponseDTO confirmBilling(
            String billingKey, String customerKey, int amount, String orderId, String orderName) {
        return tossRestClient.post()
                .uri("/v1/billing/{key}", billingKey)   // path variable
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "customerKey", customerKey,
                        "amount", amount,
                        "orderId", orderId,
                        "orderName", orderName))
                .retrieve()
                .body(TossPaymentApproveResponseDTO.class);
    }

    // 주문번호로 결제 결과 조회 (응답 유실 복구용). 토스에 주문이 없으면 404
    public TossPaymentApproveResponseDTO findByOrderId(String orderId) {
        return tossRestClient.get()
                .uri("/v1/payments/orders/{orderId}", orderId)
                .retrieve()
                .body(TossPaymentApproveResponseDTO.class);
    }

}