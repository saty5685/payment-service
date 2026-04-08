package com.deezywallet.payment.exception;

import org.springframework.http.HttpStatus;

import com.deezywallet.payment.constants.PaymentErrorCode;

/**
 * Webhook signature verification failed — HTTP 400 Bad Request.
 *
 * WHY 400 and not 401?
 *   401 implies "provide credentials to retry."
 *   A bad webhook signature means the request is fundamentally invalid —
 *   no credentials would fix it. 400 is more accurate.
 *   Gateways treat any non-200 as a delivery failure and retry — we want
 *   them to retry legitimate webhooks (transient errors) but a signature
 *   failure is never transient, so 400 prevents infinite retries.
 */
public class WebhookSignatureException extends PaymentBaseException {
	public WebhookSignatureException(String message) {
		super(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID, message, HttpStatus.BAD_REQUEST);
	}
}
