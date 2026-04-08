package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

/** Business validation failure before payment is attempted — HTTP 422 */
public class PaymentValidationException extends PaymentBaseException {
	public PaymentValidationException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.UNPROCESSABLE_ENTITY);
	}
}
