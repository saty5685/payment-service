package com.deezywallet.payment.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.dto.request.SavePaymentMethodRequest;
import com.deezywallet.payment.dto.response.PaymentMethodResponse;
import com.deezywallet.payment.entity.PaymentMethod;
import com.deezywallet.payment.exception.PaymentNotFoundException;
import com.deezywallet.payment.exception.PaymentValidationException;
import com.deezywallet.payment.mapper.PaymentMapper;
import com.deezywallet.payment.repository.PaymentMethodRepository;
import com.deezywallet.payment.security.UserPrincipal;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PaymentMethodController — manages saved payment methods (token vault).
 *
 * BASE PATH: /api/v1/payments/methods
 * AUTH:      Any authenticated user
 *
 * PCI DSS SCOPE:
 *   This controller never returns gatewayToken or bankAccountEncrypted.
 *   The PaymentMapper explicitly omits these fields.
 *   It only returns display metadata (last4, brand, expiry, VPA, IFSC).
 *
 * METHOD LIMIT: max 10 active methods per user.
 *   Prevents abuse and keeps the list manageable in the UI.
 *   Enforced at service layer before saving.
 *
 * ENDPOINT INVENTORY:
 *   GET    /methods         — list all active methods
 *   POST   /methods         — save a new tokenised method
 *   DELETE /methods/{id}    — soft-delete a method
 *   PUT    /methods/{id}/default — set as default payment method
 */
@RestController
@RequestMapping(PaymentConstants.API_PAYMENT_BASE + "/methods")
@RequiredArgsConstructor
@Slf4j
public class PaymentMethodController {

	private final PaymentMethodRepository methodRepository;
	private final PaymentMapper           mapper;

	private static final int MAX_METHODS_PER_USER = 10;

	// ── GET /api/v1/payments/methods ──────────────────────────────────────────

	/**
	 * Returns all active saved payment methods for the authenticated user.
	 * Sorted: default method first, then by createdAt descending.
	 */
	@GetMapping
	@Transactional(readOnly = true)
	public ResponseEntity<List<PaymentMethodResponse>> listMethods(
			@AuthenticationPrincipal UserPrincipal principal) {

		List<PaymentMethodResponse> methods = methodRepository
				.findByUserIdAndIsActiveTrueOrderByIsDefaultDescCreatedAtDesc(
						principal.getUserId())
				.stream()
				.map(mapper::toMethodResponse)
				.toList();

		return ResponseEntity.ok(methods);
	}

	// ── POST /api/v1/payments/methods ─────────────────────────────────────────

	/**
	 * Saves a new tokenised payment method.
	 *
	 * The gateway token was obtained by the client via Stripe.js or Razorpay
	 * checkout widget — the raw card number never touched our servers.
	 *
	 * Duplicate detection: if the same gatewayToken is submitted again,
	 * returns the existing method (idempotent).
	 *
	 * Returns 201 Created.
	 */
	@PostMapping
	@Transactional
	public ResponseEntity<PaymentMethodResponse> saveMethod(
			@Valid @RequestBody SavePaymentMethodRequest req,
			@AuthenticationPrincipal UserPrincipal principal) {

		// Duplicate check: same token already saved for this user + gateway
		var existing = methodRepository.findByUserIdAndGatewayAndGatewayTokenAndIsActiveTrue(
				principal.getUserId(), req.getGateway(), req.getGatewayToken());
		if (existing.isPresent()) {
			return ResponseEntity.ok(mapper.toMethodResponse(existing.get()));
		}

		// Enforce method limit
		long count = methodRepository.countByUserIdAndIsActiveTrue(principal.getUserId());
		if (count >= MAX_METHODS_PER_USER) {
			throw new PaymentValidationException(
					PaymentErrorCode.INVALID_PAYMENT_METHOD,
					"Maximum " + MAX_METHODS_PER_USER + " payment methods allowed. " +
							"Please remove an existing method before adding a new one.");
		}

		// If setAsDefault, clear existing defaults first
		if (req.isSetAsDefault()) {
			methodRepository.clearDefaultForUser(principal.getUserId());
		}

		// Build entity
		// Bank account number is encrypted before storage (TODO: inject EncryptionService)
		String encryptedAccountNumber = req.getBankAccountNumber() != null
				? encrypt(req.getBankAccountNumber())
				: null;

		PaymentMethod method = PaymentMethod.builder()
				.id(UUID.randomUUID().toString())
				.userId(principal.getUserId())
				.type(req.getType())
				.gateway(req.getGateway())
				.gatewayToken(req.getGatewayToken())
				.cardLast4(req.getCardLast4())
				.cardBrand(req.getCardBrand())
				.cardExpiryMonth(req.getCardExpiryMonth())
				.cardExpiryYear(req.getCardExpiryYear())
				.upiVpa(req.getUpiVpa())
				.bankAccountEncrypted(encryptedAccountNumber)
				.ifscCode(req.getIfscCode())
				.accountHolderName(req.getAccountHolderName())
				.nickname(req.getNickname())
				.isDefault(req.isSetAsDefault())
				.isActive(true)
				.build();

		methodRepository.save(method);
		log.info("Payment method saved: id={} userId={} type={}",
				method.getId(), principal.getUserId(), method.getType());

		return ResponseEntity.status(HttpStatus.CREATED)
				.body(mapper.toMethodResponse(method));
	}

	// ── DELETE /api/v1/payments/methods/{methodId} ────────────────────────────

	/**
	 * Soft-deletes a payment method (sets isActive = false).
	 *
	 * Hard delete is not supported — historical payment records reference this
	 * method ID. Soft delete preserves FK integrity and audit trail.
	 *
	 * Returns 204 No Content.
	 */
	@DeleteMapping("/{methodId}")
	@Transactional
	public ResponseEntity<Void> deleteMethod(
			@PathVariable String methodId,
			@AuthenticationPrincipal UserPrincipal principal) {

		int deleted = methodRepository.softDeleteByIdAndUserId(methodId, principal.getUserId());
		if (deleted == 0) {
			throw new PaymentNotFoundException(
					"Payment method not found: " + methodId);
		}

		log.info("Payment method soft-deleted: id={} userId={}", methodId, principal.getUserId());
		return ResponseEntity.noContent().build();
	}

	// ── PUT /api/v1/payments/methods/{methodId}/default ───────────────────────

	/**
	 * Sets a payment method as the default.
	 *
	 * Atomically:
	 *   1. Clears isDefault on all current active methods for the user.
	 *   2. Sets isDefault = true on the specified method.
	 *
	 * Returns the updated method.
	 */
	@PutMapping("/{methodId}/default")
	@Transactional
	public ResponseEntity<PaymentMethodResponse> setDefault(
			@PathVariable String methodId,
			@AuthenticationPrincipal UserPrincipal principal) {

		PaymentMethod method = methodRepository
				.findByIdAndUserIdAndIsActiveTrue(methodId, principal.getUserId())
				.orElseThrow(() -> new PaymentNotFoundException(
						"Payment method not found: " + methodId));

		// Clear all existing defaults for this user
		methodRepository.clearDefaultForUser(principal.getUserId());

		// Set this one as default
		method.setDefault(true);
		methodRepository.save(method);

		log.info("Default payment method updated: id={} userId={}", methodId, principal.getUserId());
		return ResponseEntity.ok(mapper.toMethodResponse(method));
	}

	// ── Helper ────────────────────────────────────────────────────────────────

	private String encrypt(String plaintext) {
		// TODO: inject EncryptionService and encrypt with AES-256-GCM
		return plaintext; // placeholder
	}
}
