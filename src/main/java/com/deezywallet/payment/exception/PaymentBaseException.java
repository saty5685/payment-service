package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import lombok.Getter;

@Getter
public abstract class PaymentBaseException extends RuntimeException {
	private final String     errorCode;
	private final HttpStatus httpStatus;

	protected PaymentBaseException(String errorCode, String message, HttpStatus httpStatus) {
		super(message);
		this.errorCode  = errorCode;
		this.httpStatus = httpStatus;
	}
}
