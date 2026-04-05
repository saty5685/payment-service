package com.deezywallet.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;

/**
 * JWT properties — Payment Service is a resource server only.
 * Validates tokens issued by User Service; never issues tokens.
 * Secret MUST match User Service exactly.
 */
@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {
	private String secret;
	private String issuer = "digital-wallet-platform";
}
