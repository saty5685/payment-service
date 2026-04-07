package com.deezywallet.payment.service;

import com.deezywallet.payment.constants.PaymentConstants;
import com.deezywallet.payment.constants.PaymentErrorCode;
import com.deezywallet.payment.exception.FraudRejectedException;
import com.deezywallet.payment.exception.FraudServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.Map;

/**
 * FraudCheckService — synchronous fraud evaluation before any payment is processed.
 *
 * Calls Fraud Service POST /fraud/evaluate.
 * Fail-safe: timeout or unavailability → reject the payment (not approve).
 * Score > threshold → reject.
 * Generic message to client — never reveal score or model details.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FraudCheckService {

	@Qualifier("internalRestTemplate")
	private final RestTemplate internalRestTemplate;

	@Value("${services.fraud-service.base-url}")
	private String fraudServiceBaseUrl;

	@SuppressWarnings("unchecked")
	public BigDecimal evaluate(String userId, BigDecimal amount, String paymentType) {
		try {
			Map<String, Object> request = Map.of(
					"userId",      userId,
					"amount",      amount,
					"paymentType", paymentType
			);

			ResponseEntity<Map> response = internalRestTemplate.postForEntity(
					fraudServiceBaseUrl + "/fraud/evaluate",
					request,
					Map.class
			);

			Map<String, Object> body = response.getBody();
			if (body == null) {
				throw new FraudServiceException(PaymentErrorCode.FRAUD_SERVICE_UNAVAILABLE,
						"Risk assessment service is temporarily unavailable.");
			}

			double score    = ((Number) body.getOrDefault("score", 0.0)).doubleValue();
			String decision = (String) body.getOrDefault("decision", "APPROVE");

			if ("DECLINE".equals(decision) || score > PaymentConstants.FRAUD_SCORE_THRESHOLD) {
				log.warn("Payment rejected by fraud: userId={} score={}", userId, score);
				throw new FraudRejectedException(PaymentErrorCode.FRAUD_SCORE_EXCEEDED,
						"Transaction declined due to risk assessment");
			}

			return BigDecimal.valueOf(score);

		} catch (ResourceAccessException e) {
			log.error("Fraud Service timeout for userId={}: {}", userId, e.getMessage());
			throw new FraudServiceException(PaymentErrorCode.FRAUD_SERVICE_UNAVAILABLE,
					"Risk assessment service is temporarily unavailable. Please try again.");
		}
	}
}
