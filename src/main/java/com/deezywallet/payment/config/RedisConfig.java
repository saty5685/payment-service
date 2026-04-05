package com.deezywallet.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for Payment Service.
 *
 * Use cases:
 *   - Idempotency lock:  pay:lock:{idempotencyKey}    → "1"  (SETNX, 1h TTL)
 *   - 3DS session:       pay:3ds:{paymentId}           → JSON metadata (15-min TTL)
 *   - Webhook dedup lock: pay:webhook:{gatewayEventId} → "1"  (24h TTL)
 *
 * All plain string keys and values — consistent with other services.
 */
@Configuration
public class RedisConfig {

	@Bean
	public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory factory) {
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(factory);
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(new StringRedisSerializer());
		template.setHashKeySerializer(new StringRedisSerializer());
		template.setHashValueSerializer(new StringRedisSerializer());
		template.afterPropertiesSet();
		return template;
	}
}
