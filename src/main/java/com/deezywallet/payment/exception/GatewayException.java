package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

/** Gateway 5xx or timeout — HTTP 503 Service Unavailable */
public class GatewayException extends PaymentBaseException {
	public GatewayException(String errorCode, String message) {
		super(errorCode, message, HttpStatus.SERVICE_UNAVAILABLE);
	}
}
