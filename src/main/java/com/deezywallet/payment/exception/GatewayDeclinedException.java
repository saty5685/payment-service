package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

/**
 * Card declined by the gateway — HTTP 422 Unprocessable Entity.
 *
 * Carries declineCode internally for logging/audit, but the message
 * returned to the client is always user-friendly (not the raw gateway code).
 */
@Getter
public class GatewayDeclinedException extends PaymentBaseException {
	private final String declineCode;  // raw gateway code — NEVER returned to client

	public GatewayDeclinedException(String errorCode, String declineCode, String userMessage) {
		super(errorCode, userMessage, HttpStatus.UNPROCESSABLE_ENTITY);
		this.declineCode = declineCode;
	}
}
