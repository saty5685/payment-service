package com.deezywallet.payment.controller;

import java.math.BigDecimal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.dto.response.AdminPaymentResponse;
import com.deezywallet.payment.dto.response.PagedResponse;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.security.UserPrincipal;
import com.deezywallet.payment.service.PaymentService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * AdminPaymentController — payment management for platform admins.
 *
 * BASE PATH: /api/v1/admin/payments
 * AUTH:      ROLE_ADMIN (enforced at SecurityConfig route level)
 *
 * Returns AdminPaymentResponse — includes internal fields (fraudScore,
 * gatewayChargeId, gatewayDeclineCode, idempotencyKey, version) hidden
 * from regular users.
 *
 * The adminId used in refund audit trails comes from the JWT principal —
 * never accepted from the request body (server-authoritative audit trail).
 *
 * ENDPOINT INVENTORY:
 *   GET  /                  — list all payments (paginated, status filter)
 *   GET  /{paymentId}       — get any payment with full internal fields
 *   POST /{paymentId}/refund — issue a full or partial refund
 */
@RestController
@RequestMapping(PaymentConstants.API_ADMIN_BASE)
@RequiredArgsConstructor
@Slf4j
public class AdminPaymentController {

	private final PaymentService paymentService;

	// ── GET /api/v1/admin/payments ────────────────────────────────────────────

	@GetMapping
	public ResponseEntity<PagedResponse<AdminPaymentResponse>> listPayments(
			@RequestParam(required = false) PaymentStatusEnum status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable) {

		return ResponseEntity.ok(paymentService.listPayments(status, pageable));
	}

	// ── GET /api/v1/admin/payments/{paymentId} ────────────────────────────────

	@GetMapping("/{paymentId}")
	public ResponseEntity<AdminPaymentResponse> getPayment(@PathVariable String paymentId) {
		return ResponseEntity.ok(paymentService.getPaymentAdmin(paymentId));
	}

	// ── POST /api/v1/admin/payments/{paymentId}/refund ────────────────────────

	/**
	 * Issues a full or partial refund back to the original payment method.
	 *
	 * Only CAPTURED or PARTIALLY_REFUNDED payments are refundable.
	 * If refundAmount is not specified, a full refund is issued.
	 *
	 * The adminId is captured from the JWT principal — immutable audit trail.
	 * Returns 200 with the updated AdminPaymentResponse.
	 *
	 * Body: { "refundAmount": 500.00 }  (optional — full refund if absent)
	 */
	@PostMapping("/{paymentId}/refund")
	public ResponseEntity<AdminPaymentResponse> refundPayment(
			@PathVariable String paymentId,
			@RequestBody(required = false) java.util.Map<String, BigDecimal> body,
			@AuthenticationPrincipal UserPrincipal adminPrincipal) {

		// If refundAmount not provided, full refund (service will validate)
		BigDecimal refundAmount = (body != null) ? body.get("refundAmount") : null;

		log.info("Admin refund requested: paymentId={} amount={} adminId={}",
				paymentId, refundAmount, adminPrincipal.getUserId());

		AdminPaymentResponse response = paymentService.refundPayment(
				paymentId, refundAmount, adminPrincipal.getUserId());

		return ResponseEntity.ok(response);
	}
}
