package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

/** Fraud Service unavailable — HTTP 503 */
public class FraudServiceException extends PaymentBaseException {
	public FraudServiceException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.SERVICE_UNAVAILABLE);
	}
}
