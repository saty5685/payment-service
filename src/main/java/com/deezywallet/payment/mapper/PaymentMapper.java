package com.deezywallet.payment.mapper;

import org.springframework.stereotype.Component;

import com.deezywallet.payment.dto.response.AdminPaymentResponse;
import com.deezywallet.payment.dto.response.PaymentMethodResponse;
import com.deezywallet.payment.dto.response.PaymentResponse;
import com.deezywallet.payment.dto.response.WithdrawalResponse;
import com.deezywallet.payment.entity.Payment;
import com.deezywallet.payment.entity.PaymentMethod;
import com.deezywallet.payment.entity.WithdrawalRequest;
import com.deezywallet.payment.enums.PaymentStatusEnum;
import com.deezywallet.payment.enums.WithdrawalStatusEnum;

/**
 * Hand-written mapper — enforces security boundaries between
 * user-facing and admin-facing responses.
 *
 * USER response: no fraudScore, no gatewayChargeId, no gatewayDeclineCode,
 *                no idempotencyKey, no version — internal fields only.
 *
 * ADMIN response: all fields including internal ones.
 *
 * PaymentMethod response: no gatewayToken (credential), no bankAccountEncrypted.
 *
 * statusDescription is computed here — not stored in DB — so the
 * human-readable copy can evolve without schema migrations.
 */
@Component
public class PaymentMapper {

	// ── Payment → PaymentResponse (user-safe) ────────────────────────────────

	public PaymentResponse toResponse(Payment payment) {
		if (payment == null) return null;
		return PaymentResponse.builder()
				.paymentId(payment.getId())
				.type(payment.getType())
				.status(payment.getStatus())
				.statusDescription(describePaymentStatus(payment.getStatus()))
				.amount(payment.getAmount())
				.currency(payment.getCurrency())
				.gateway(payment.getGateway())
				.paymentMethodId(payment.getPaymentMethodId())
				// threeDsRedirectUrl: only include when status is PENDING_3DS
				.threeDsRedirectUrl(
						payment.getStatus() == PaymentStatusEnum.PENDING_3DS
								? payment.getThreeDsRedirectUrl()
								: null
				)
				.createdAt(payment.getCreatedAt())
				.capturedAt(payment.getCapturedAt())
				.build();
	}

	// ── Payment → AdminPaymentResponse (all fields) ───────────────────────────

	public AdminPaymentResponse toAdminResponse(Payment payment) {
		if (payment == null) return null;
		return AdminPaymentResponse.builder()
				.paymentId(payment.getId())
				.userId(payment.getUserId())
				.walletId(payment.getWalletId())
				.type(payment.getType())
				.status(payment.getStatus())
				.statusDescription(describePaymentStatus(payment.getStatus()))
				.amount(payment.getAmount())
				.currency(payment.getCurrency())
				.refundedAmount(payment.getRefundedAmount())
				.gateway(payment.getGateway())
				.paymentMethodId(payment.getPaymentMethodId())
				.gatewayChargeId(payment.getGatewayChargeId())
				.gatewayEventId(payment.getGatewayEventId())
				.gatewayDeclineCode(payment.getGatewayDeclineCode())
				.fraudScore(payment.getFraudScore())
				.failureReason(payment.getFailureReason())
				.idempotencyKey(payment.getIdempotencyKey())
				.threeDsRedirectUrl(payment.getThreeDsRedirectUrl())
				.createdAt(payment.getCreatedAt())
				.capturedAt(payment.getCapturedAt())
				.version(payment.getVersion())
				.build();
	}

	// ── PaymentMethod → PaymentMethodResponse ─────────────────────────────────

	public PaymentMethodResponse toMethodResponse(PaymentMethod method) {
		if (method == null) return null;
		return PaymentMethodResponse.builder()
				.methodId(method.getId())
				.type(method.getType())
				.gateway(method.getGateway())
				.isDefault(method.isDefault())
				.nickname(method.getNickname())
				// Card display metadata
				.cardLast4(method.getCardLast4())
				.cardBrand(method.getCardBrand())
				.cardExpiryMonth(method.getCardExpiryMonth())
				.cardExpiryYear(method.getCardExpiryYear())
				.cardExpired(method.isCardExpired())
				// UPI
				.upiVpa(method.getUpiVpa())
				// Bank — no encrypted account number, only display fields
				.accountHolderName(method.getAccountHolderName())
				.ifscCode(method.getIfscCode())
				.createdAt(method.getCreatedAt())
				.build();
		// NOTE: gatewayToken and bankAccountEncrypted are DELIBERATELY omitted.
	}

	// ── WithdrawalRequest → WithdrawalResponse ────────────────────────────────

	public WithdrawalResponse toWithdrawalResponse(WithdrawalRequest withdrawal) {
		if (withdrawal == null) return null;
		return WithdrawalResponse.builder()
				.withdrawalId(withdrawal.getId())
				.status(withdrawal.getStatus())
				.statusDescription(describeWithdrawalStatus(withdrawal.getStatus()))
				.amount(withdrawal.getAmount())
				.currency(withdrawal.getCurrency())
				.bankAccountMethodId(withdrawal.getPaymentMethodId())
				.neftImpsReference(withdrawal.getNeftImpsReference())
				.createdAt(withdrawal.getCreatedAt())
				.completedAt(withdrawal.getCompletedAt())
				.build();
	}

	// ── Status descriptions ───────────────────────────────────────────────────

	private String describePaymentStatus(PaymentStatusEnum status) {
		if (status == null) return null;
		return switch (status) {
			case PENDING              -> "Processing your payment";
			case PENDING_3DS          -> "Authentication required — please complete 3D Secure";
			case CAPTURED             -> "Payment successful";
			case FAILED               -> "Payment failed";
			case EXPIRED              -> "Payment session expired — please try again";
			case REFUNDED             -> "Full refund processed";
			case PARTIALLY_REFUNDED   -> "Partial refund processed";
		};
	}

	private String describeWithdrawalStatus(WithdrawalStatusEnum status) {
		if (status == null) return null;
		return switch (status) {
			case WALLET_DEBIT_PENDING  -> "Initiating withdrawal";
			case BANK_TRANSFER_PENDING -> "Transfer in progress — funds typically arrive within 2 hours";
			case COMPLETED             -> "Withdrawal successful — funds credited to your bank account";
			case FAILED                -> "Withdrawal failed";
			case REVERSED              -> "Withdrawal reversed — funds returned to your wallet";
		};
	}
}
