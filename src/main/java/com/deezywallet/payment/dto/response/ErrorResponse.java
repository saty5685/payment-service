package com.deezywallet.payment.dto.response;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

	private String            errorCode;
	private String            message;
	private LocalDateTime     timestamp;
	private List<FieldError>  fieldErrors;

	@Data
	@AllArgsConstructor
	@NoArgsConstructor
	public static class FieldError {
		private String field;
		private String message;
	}

	public static ErrorResponse of(String errorCode, String message) {
		return ErrorResponse.builder()
				.errorCode(errorCode)
				.message(message)
				.timestamp(LocalDateTime.now())
				.build();
	}

	public static ErrorResponse ofValidation(String errorCode, List<FieldError> errors) {
		return ErrorResponse.builder()
				.errorCode(errorCode)
				.message("Validation failed")
				.timestamp(LocalDateTime.now())
				.fieldErrors(errors)
				.build();
	}
}
