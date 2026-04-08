package com.deezywallet.payment.controller;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.dto.request.TopUpRequest;
import com.deezywallet.payment.dto.request.WithdrawalRequest;
import com.deezywallet.payment.dto.response.PagedResponse;
import com.deezywallet.payment.dto.response.PaymentResponse;
import com.deezywallet.payment.dto.response.WithdrawalResponse;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.security.UserPrincipal;
import com.deezywallet.payment.service.PaymentService;
import com.deezywallet.payment.service.WithdrawalService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PaymentController — user-facing payment and withdrawal endpoints.
 *
 * BASE PATH: /api/v1/payments
 * AUTH:      Any authenticated user (ROLE_USER)
 *
 * IDEMPOTENCY-KEY HEADER:
 *   Required on all mutation endpoints (POST /topup, POST /withdraw).
 *   Same convention as Transaction Service — header signals to API gateways
 *   that the endpoint is idempotent. Body's idempotencyKey is the authoritative
 *   value for business logic; header is for infrastructure signalling.
 *
 * RESPONSE CODES:
 *   POST /topup    → 202 Accepted  — payment is initiated, may still require 3DS
 *                    OR 200 OK     — if duplicate idempotency key (existing payment returned)
 *   POST /withdraw → 202 Accepted  — withdrawal is asynchronous (NPCI transfer)
 *   GET  /me       → 200 OK
 *   GET  /me/{id}  → 200 OK
 *
 * WHY 202 for top-up?
 *   The payment may be in PENDING_3DS — the client needs to redirect the user
 *   before the payment is complete. 202 correctly signals "initiated but not
 *   yet finalised." The response body includes threeDsRedirectUrl when needed.
 */
@RestController
@RequestMapping(PaymentConstants.API_PAYMENT_BASE)
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

	private final PaymentService    paymentService;
	private final WithdrawalService withdrawalService;

	// ── POST /api/v1/payments/topup ───────────────────────────────────────────

	/**
	 * Initiates a wallet top-up via card, UPI, or NetBanking.
	 *
	 * Response states:
	 *   CAPTURED      → payment succeeded, wallet will be credited shortly
	 *   PENDING_3DS   → redirect to threeDsRedirectUrl for bank authentication
	 *   (FAILED/DECLINED → thrown as GatewayDeclinedException → 422)
	 *
	 * On duplicate idempotencyKey: returns existing payment with 200 OK.
	 * On new payment: returns 202 Accepted.
	 */
	@PostMapping("/topup")
	public ResponseEntity<PaymentResponse> topUp(
			@Valid @RequestBody TopUpRequest req,
			@RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
			@AuthenticationPrincipal UserPrincipal principal) {

		PaymentResponse response = paymentService.initiateTopUp(req, principal);

		// 200 for idempotent repeat (terminal or in-progress state already exists)
		// 202 for newly initiated payment (PENDING or PENDING_3DS)
		HttpStatus status = (response.getStatus() == PaymentStatusEnum.PENDING ||
				response.getStatus() == PaymentStatusEnum.PENDING_3DS)
				? HttpStatus.ACCEPTED
				: HttpStatus.OK;

		return ResponseEntity.status(status).body(response);
	}

	// ── POST /api/v1/payments/withdraw ────────────────────────────────────────

	/**
	 * Initiates a bank withdrawal from the user's wallet.
	 *
	 * The withdrawal is async — NPCI NEFT/IMPS can take up to 2 hours.
	 * Response includes the UTR (neftImpsReference) for user reference.
	 * Returns 202 Accepted — funds are in transit.
	 */
	@PostMapping("/withdraw")
	public ResponseEntity<WithdrawalResponse> withdraw(
			@Valid @RequestBody WithdrawalRequest req,
			@RequestHeader("Idempotency-Key") String idempotencyKeyHeader,
			@AuthenticationPrincipal UserPrincipal principal) {

		WithdrawalResponse response = withdrawalService.initiateWithdrawal(req, principal);
		return ResponseEntity.accepted().body(response);
	}

	// ── GET /api/v1/payments/me ───────────────────────────────────────────────

	/**
	 * Returns the authenticated user's payment history.
	 * Paginated, sorted by createdAt descending. Optional status filter.
	 */
	@GetMapping("/me")
	public ResponseEntity<PagedResponse<PaymentResponse>> getMyPayments(
			@AuthenticationPrincipal UserPrincipal principal,
			@RequestParam(required = false) PaymentStatusEnum status,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable) {

		return ResponseEntity.ok(
				paymentService.getUserPayments(principal.getUserId(), status, pageable));
	}

	// ── GET /api/v1/payments/me/{paymentId} ───────────────────────────────────

	/**
	 * Returns a specific payment for the authenticated user.
	 *
	 * IDOR-safe: returns 404 for both "not found" and "not owned by this user."
	 * An attacker cannot distinguish between the two cases.
	 */
	@GetMapping("/me/{paymentId}")
	public ResponseEntity<PaymentResponse> getMyPayment(
			@PathVariable String paymentId,
			@AuthenticationPrincipal UserPrincipal principal) {

		return ResponseEntity.ok(
				paymentService.getPayment(paymentId, principal.getUserId()));
	}

	// ── GET /api/v1/payments/me/withdrawals ───────────────────────────────────

	/**
	 * Returns the authenticated user's withdrawal history.
	 */
	@GetMapping("/me/withdrawals")
	public ResponseEntity<PagedResponse<WithdrawalResponse>> getMyWithdrawals(
			@AuthenticationPrincipal UserPrincipal principal,
			@PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
			Pageable pageable) {

		return ResponseEntity.ok(
				withdrawalService.getUserWithdrawals(principal.getUserId(), pageable));
	}
}
