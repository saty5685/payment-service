package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * Transaction rejected by fraud evaluation — HTTP 403 Forbidden.
 * Generic message — never reveal score or model thresholds.
 */
public class FraudRejectedException extends PaymentBaseException {
	public FraudRejectedException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.FORBIDDEN);
	}
}
