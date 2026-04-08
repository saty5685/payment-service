package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import com.deezywallet.payment.constants.PaymentErrorCode;

/** Idempotency key conflict — lock held but DB record not yet written — HTTP 409 */
public class DuplicatePaymentException extends PaymentBaseException {
	public DuplicatePaymentException(String message) {
		super(PaymentErrorCode.DUPLICATE_PAYMENT, message, HttpStatus.CONFLICT);
	}
}
