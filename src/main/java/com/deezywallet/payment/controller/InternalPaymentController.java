package com.deezywallet.payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.dto.response.PaymentResponse;
import com.deezywallet.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;

/**
 * InternalPaymentController — machine-to-machine endpoints.
 *
 * BASE PATH: /internal/v1/payments
 * AUTH:      ROLE_INTERNAL_SERVICE (enforced at SecurityConfig route level)
 *
 * WHO CALLS THIS:
 *   - Ledger Service → GET /{paymentId}/status to enrich ledger entries
 *     with payment metadata (amount, gateway, charge ID for reconciliation)
 *
 * NETWORK ISOLATION:
 *   /internal/** is blocked at the API Gateway.
 *   Only reachable within the Kubernetes cluster via service DNS.
 *
 * Returns PaymentResponse (not AdminPaymentResponse) — no fraudScore,
 * no gatewayDeclineCode for internal callers either. Principle of least info.
 *
 * ENDPOINT INVENTORY:
 *   GET /{paymentId}/status — payment status by ID
 */
@RestController
@RequestMapping(PaymentConstants.API_INTERNAL_BASE)
@RequiredArgsConstructor
public class InternalPaymentController {

	private final PaymentService paymentService;

	/**
	 * Returns payment status for internal service consumption.
	 * No ownership check — ROLE_INTERNAL_SERVICE callers are trusted.
	 */
	@GetMapping("/{paymentId}/status")
	public ResponseEntity<PaymentResponse> getPaymentStatus(
			@PathVariable String paymentId) {
		return ResponseEntity.ok(paymentService.getPaymentInternal(paymentId));
	}
}
