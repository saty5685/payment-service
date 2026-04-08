package com.deezywallet.payment.exception;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.dto.response.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised exception-to-HTTP mapping for Payment Service.
 *
 * HANDLER HIERARCHY:
 * ─────────────────────────────────────────────────────────────────────
 *  FraudRejectedException     → 403 (generic message — no score exposed)
 *  GatewayDeclinedException   → 422 (user-friendly message — no declineCode)
 *  WebhookSignatureException  → 400 (prevents gateway retry storms)
 *  PaymentBaseException       → uses embedded httpStatus
 *  MethodArgumentNotValidException → 400 with field errors
 *  MissingRequestHeaderException  → 400 (missing Idempotency-Key)
 *  DataIntegrityViolationException → 409 (idempotency_key race)
 *  ObjectOptimisticLockingFailureException → 409
 *  AccessDeniedException      → 403
 *  Exception                  → 500, no internal detail
 * ─────────────────────────────────────────────────────────────────────
 *
 * SECURITY RULES:
 *   FraudRejectedException: hardcodes generic message — never echoes score.
 *   GatewayDeclinedException: returns user-friendly message from exception,
 *     never the raw declineCode field (e.g. "do_not_honor").
 *   WebhookSignatureException: returns 400 not 401 — prevents retry storms.
 *   Exception catch-all: full stack trace logged internally, nothing useful externally.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

	// ── Fraud — always generic ────────────────────────────────────────────────

	@ExceptionHandler(FraudRejectedException.class)
	public ResponseEntity<ErrorResponse> handleFraudRejected(FraudRejectedException ex) {
		// Never echo model details — hardcoded generic message
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of(ex.getErrorCode(),
						"Transaction declined due to risk assessment"));
	}

	// ── Gateway decline — user-friendly message, no declineCode ──────────────

	@ExceptionHandler(GatewayDeclinedException.class)
	public ResponseEntity<ErrorResponse> handleDeclined(GatewayDeclinedException ex) {
		// ex.getMessage() returns the user-friendly message set in the exception.
		// ex.getDeclineCode() is the raw gateway code — NEVER returned here.
		log.warn("Payment declined: errorCode={} declineCode={}", ex.getErrorCode(), ex.getDeclineCode());
		return ResponseEntity.status(ex.getHttpStatus())
				.body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
	}

	// ── Webhook signature — 400 to prevent gateway retry storms ──────────────

	@ExceptionHandler(WebhookSignatureException.class)
	public ResponseEntity<ErrorResponse> handleWebhookSig(WebhookSignatureException ex) {
		log.warn("Webhook signature verification failed: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.of(ex.getErrorCode(), "Webhook verification failed"));
	}

	// ── All other domain exceptions ───────────────────────────────────────────

	@ExceptionHandler(PaymentBaseException.class)
	public ResponseEntity<ErrorResponse> handleDomain(PaymentBaseException ex) {
		if (ex.getHttpStatus().is5xxServerError()) {
			log.error("Payment domain exception: code={} status={} msg={}",
					ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage());
		} else {
			log.warn("Payment domain exception: code={} status={} msg={}",
					ex.getErrorCode(), ex.getHttpStatus(), ex.getMessage());
		}
		return ResponseEntity.status(ex.getHttpStatus())
				.body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
	}

	// ── Validation (@Valid on request bodies) ─────────────────────────────────

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
		List<ErrorResponse.FieldError> errors = ex.getBindingResult()
				.getFieldErrors()
				.stream()
				.map(fe -> new ErrorResponse.FieldError(fe.getField(), fe.getDefaultMessage()))
				.toList();

		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.ofValidation(PaymentErrorCode.VALIDATION_FAILED, errors));
	}

	// ── Missing Idempotency-Key header ────────────────────────────────────────

	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.of(PaymentErrorCode.VALIDATION_FAILED,
						"Required header missing: " + ex.getHeaderName()));
	}

	// ── DB constraint race (idempotency_key / gateway_event_id UNIQUE) ────────

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
		log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of(PaymentErrorCode.DUPLICATE_PAYMENT,
						"A payment with this idempotency key already exists"));
	}

	// ── Optimistic lock conflict ───────────────────────────────────────────────

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ResponseEntity<ErrorResponse> handleOptimisticLock(
			ObjectOptimisticLockingFailureException ex) {
		log.warn("Optimistic lock conflict on payment: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(ErrorResponse.of(PaymentErrorCode.PAYMENT_ALREADY_TERMINAL,
						"Payment was concurrently modified. Please retry."));
	}

	// ── Spring Security ───────────────────────────────────────────────────────

	@ExceptionHandler(AccessDeniedException.class)
	public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(ErrorResponse.of(PaymentErrorCode.ACCESS_DENIED, "Insufficient permissions"));
	}

	// ── Catch-all — never leak internal detail ────────────────────────────────

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Unhandled exception in Payment Service: {}", ex.getMessage(), ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ErrorResponse.of(PaymentErrorCode.INTERNAL_ERROR,
						"An unexpected error occurred. Please try again."));
	}
}
