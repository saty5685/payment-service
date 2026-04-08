package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import com.deezywallet.payment.constants.PaymentErrorCode;

/** Payment or withdrawal not found — HTTP 404 */
public class PaymentNotFoundException extends PaymentBaseException {
	public PaymentNotFoundException(String message) {
		super(PaymentErrorCode.PAYMENT_NOT_FOUND, message, HttpStatus.NOT_FOUND);
	}
}
