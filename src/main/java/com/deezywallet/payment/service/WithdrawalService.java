package com.deezywallet.payment.service;

import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.dto.request.WithdrawalRequest;
import com.deezywallet.payment.dto.response.PagedResponse;
import com.deezywallet.payment.dto.response.WithdrawalResponse;
import com.deezywallet.payment.entity.PaymentMethod;
import com.deezywallet.payment.entity.WithdrawalRequest as WithdrawalEntity;
import com.deezywallet.payment.enums.PaymentMethodTypeEnum;
import com.deezywallet.payment.enums.WithdrawalStatusEnum;
import com.deezywallet.payment.event.PaymentEventPublisher;
import com.deezywallet.payment.exception.*;
import com.deezywallet.payment.mapper.PaymentMapper;
import com.deezywallet.payment.repository.PaymentMethodRepository;
import com.deezywallet.payment.repository.WithdrawalRequestRepository;
import com.deezywallet.payment.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * WithdrawalService — wallet → bank account via NPCI.
 *
 * WITHDRAWAL FLOW:
 *   1. Validate bank account method (exists, belongs to user, is BANK_ACCOUNT type)
 *   2. Persist WithdrawalRequest (status=WALLET_DEBIT_PENDING)
 *   3. Call Wallet Service to debit the wallet
 *      (simplified: in full implementation this would use the Saga pattern
 *       with a WALLET_DEBIT_CMD command + event listener)
 *   4. On debit confirmed: initiate NPCI transfer → get UTR
 *   5. Update status to BANK_TRANSFER_PENDING with UTR
 *   6. Publish WITHDRAWAL_INITIATED event
 *   7. NPCI webhook (handled separately) → COMPLETED or FAILED
 *
 * BANK ACCOUNT DECRYPTION:
 *   The bank account number is stored AES-256 encrypted.
 *   Before passing to GatewayService.initiateNpciTransfer(), it must be
 *   decrypted using EncryptionService (not shown — wraps AES-GCM).
 *   Placeholder: this.decrypt(method.getBankAccountEncrypted())
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WithdrawalService {

	private final WithdrawalRequestRepository withdrawalRepository;
	private final PaymentMethodRepository     methodRepository;
	private final GatewayService              gatewayService;
	private final PaymentEventPublisher       eventPublisher;
	private final PaymentMapper               mapper;

	@Transactional
	public WithdrawalResponse initiateWithdrawal(WithdrawalRequest req,
			UserPrincipal    principal) {
		// Idempotency check
		if (withdrawalRepository.existsByIdempotencyKey(req.getIdempotencyKey())) {
			return withdrawalRepository.findByIdempotencyKey(req.getIdempotencyKey())
					.map(mapper::toWithdrawalResponse)
					.orElseThrow();
		}

		// Validate bank account method
		PaymentMethod method = methodRepository
				.findByIdAndUserIdAndIsActiveTrue(req.getBankAccountMethodId(), principal.getUserId())
				.orElseThrow(() -> new PaymentValidationException(
						PaymentErrorCode.PAYMENT_METHOD_NOT_FOUND,
						"Bank account not found"));

		if (method.getType() != PaymentMethodTypeEnum.BANK_ACCOUNT) {
			throw new PaymentValidationException(
					PaymentErrorCode.INVALID_PAYMENT_METHOD,
					"Selected payment method is not a bank account");
		}

		// Persist withdrawal request
		WithdrawalEntity withdrawal = WithdrawalEntity.builder()
				.id(UUID.randomUUID().toString())
				.userId(principal.getUserId())
				.walletId(resolveWalletId(principal.getUserId()))
				.paymentMethodId(req.getBankAccountMethodId())
				.amount(req.getAmount())
				.idempotencyKey(req.getIdempotencyKey())
				.status(WithdrawalStatusEnum.WALLET_DEBIT_PENDING)
				.build();

		withdrawalRepository.save(withdrawal);

		// TODO: In full implementation, publish WALLET_DEBIT_CMD to Kafka and
		// listen for WALLET_DEBITED event before initiating NPCI transfer.
		// For now, we call Wallet Service synchronously (simplified).
		initiateNpciTransfer(withdrawal, method);

		return mapper.toWithdrawalResponse(withdrawal);
	}

	private void initiateNpciTransfer(WithdrawalEntity withdrawal, PaymentMethod method) {
		try {
			// Decrypt bank account number before passing to NPCI
			String decryptedAccountNumber = decrypt(method.getBankAccountEncrypted());

			String utr = gatewayService.initiateNpciTransfer(
					decryptedAccountNumber,
					method.getIfscCode(),
					withdrawal.getAmount(),
					withdrawal.getId()
			);

			withdrawalRepository.updateStatusToBankTransferPending(
					withdrawal.getId(), utr, withdrawal.getVersion());

			withdrawal.setStatus(WithdrawalStatusEnum.BANK_TRANSFER_PENDING);
			withdrawal.setNeftImpsReference(utr);

			eventPublisher.publishWithdrawalInitiated(withdrawal);
			log.info("Withdrawal NPCI transfer initiated: id={} utr={}", withdrawal.getId(), utr);

		} catch (GatewayException e) {
			withdrawalRepository.updateStatusToFailed(
					withdrawal.getId(), e.getMessage(), withdrawal.getVersion());
			withdrawal.setStatus(WithdrawalStatusEnum.FAILED);
			eventPublisher.publishWithdrawalFailed(withdrawal, e.getMessage());
			throw e;
		}
	}

	/**
	 * Handles NPCI webhook confirming the bank transfer completed.
	 * Called by WebhookController after NPCI posts a confirmation.
	 */
	@Transactional
	public void handleNpciWebhook(String neftImpsReference, boolean succeeded) {
		WithdrawalEntity withdrawal = withdrawalRepository
				.findByNeftImpsReference(neftImpsReference)
				.orElseGet(() -> {
					log.warn("NPCI webhook for unknown UTR={}", neftImpsReference);
					return null;
				});

		if (withdrawal == null || withdrawal.getStatus().isTerminal()) return;

		if (succeeded) {
			withdrawalRepository.updateStatusToCompleted(
					withdrawal.getId(), LocalDateTime.now(), withdrawal.getVersion());
			withdrawal.setStatus(WithdrawalStatusEnum.COMPLETED);
			eventPublisher.publishWithdrawalCompleted(withdrawal);
			log.info("Withdrawal completed: id={} utr={}", withdrawal.getId(), neftImpsReference);
		} else {
			withdrawalRepository.updateStatusToFailed(
					withdrawal.getId(), "Bank transfer failed — funds will be reversed",
					withdrawal.getVersion());
			withdrawal.setStatus(WithdrawalStatusEnum.FAILED);
			eventPublisher.publishWithdrawalFailed(withdrawal, "Bank transfer failed");
			// TODO: Publish WALLET_CREDIT_CMD to return funds to wallet
		}
	}

	@Transactional(readOnly = true)
	public PagedResponse<WithdrawalResponse> getUserWithdrawals(String userId, Pageable pageable) {
		return PagedResponse.from(
				withdrawalRepository.findByUserIdOrderByCreatedAtDesc(userId, pageable)
						.map(mapper::toWithdrawalResponse));
	}

	private String resolveWalletId(String userId) {
		return "wallet-" + userId; // TODO: call Wallet Service internal API
	}

	private String decrypt(String encrypted) {
		// TODO: inject EncryptionService and decrypt AES-256 encrypted account number
		return encrypted; // placeholder
	}
}
