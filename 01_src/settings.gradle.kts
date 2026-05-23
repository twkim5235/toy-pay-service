rootProject.name = "payment-platform"

// 현재 스코프: 결제 본체(payment) + 서비스간 이벤트 계약(event-contracts)
// 추후 취소/환불 단계에서 :risk-management, :refund-limit, :merchant-webhook 추가
include(":event-contracts")
include(":payment")
