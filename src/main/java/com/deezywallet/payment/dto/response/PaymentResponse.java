package com.deezywallet.payment.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.deezywallet.payment.enums.PaymentGatewayEnum;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.PaymentTypeEnum;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Payment response — returned to authenticated users.
 *
 * Omits: fraudScore, gatewayDeclineCode, gatewayChargeId.
 *   fraudScore — never expose model internals to clients.
 *   gatewayDeclineCode — gateway-specific codes (e.g. "do_not_honor") are
 *     internal; clients see the human-readable statusDescription.
 *   gatewayChargeId — internal reconciliation reference, not for clients.
 *
 * threeDsRedirectUrl — included ONLY when status = PENDING_3DS.
 *   The client uses this URL to redirect the user for bank authentication.
 *   @JsonInclude(NON_NULL) ensures it's absent from all other responses.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PaymentResponse {

	private String            paymentId;
	private PaymentTypeEnum   type;
	private PaymentStatusEnum status;
	private String            statusDescription;  // human-readable

	private BigDecimal        amount;
	private String            currency;

	private PaymentGatewayEnum gateway;
	private String            paymentMethodId;    // which method was used

	/**
	 * Only present when status = PENDING_3DS.
	 * Client redirects the browser here for cardholder authentication.
	 */
	private String            threeDsRedirectUrl;

	private LocalDateTime     createdAt;
	private LocalDateTime     capturedAt;         // null until CAPTURED
}
