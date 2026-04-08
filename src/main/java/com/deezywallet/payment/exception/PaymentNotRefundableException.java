package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import com.deezywallet.payment.constants.PaymentErrorCode;

/** Payment is not in a refundable state — HTTP 409 Conflict */
public class PaymentNotRefundableException extends PaymentBaseException {
	public PaymentNotRefundableException(String message) {
		super(PaymentErrorCode.PAYMENT_NOT_REFUNDABLE, message, HttpStatus.CONFLICT);
	}
}
