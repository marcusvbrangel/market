package com.market.order.interfaces.rest;

import com.market.order.application.DuplicateProductException;
import com.market.order.application.IdempotencyConflictException;
import com.market.order.application.InvalidIdempotencyKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;

import java.util.ArrayList;

@RestControllerAdvice
class OrderApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidRequest(MethodArgumentNotValidException exception) {
        var violations = new ArrayList<ApiProblemResponse.FieldViolation>();

        for (var fieldError : exception.getBindingResult().getFieldErrors()) {
            var violation = new ApiProblemResponse.FieldViolation(
                    fieldError.getField(),
                    fieldError.getDefaultMessage()
            );
            violations.add(violation);
        }

        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request validation failed"
        );
        problem.setTitle("Invalid request");
        problem.setProperty("code", "INVALID_REQUEST");
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadableMessage(HttpMessageNotReadableException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body is malformed or contains unsupported fields"
        );
        problem.setTitle("Invalid request body");
        problem.setProperty("code", "INVALID_REQUEST_BODY");
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleMissingRequestHeader(MissingRequestHeaderException exception) {
        var problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Required request header is missing: " + exception.getHeaderName()
        );
        problem.setTitle("Missing request header");

        if ("Idempotency-Key".equals(exception.getHeaderName())) {
            problem.setProperty("code", "IDEMPOTENCY_KEY_REQUIRED");
            return problem;
        }

        problem.setProperty("code", "REQUIRED_HEADER_MISSING");
        return problem;
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail handleInvalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid idempotency key");
        problem.setProperty("code", "INVALID_IDEMPOTENCY_KEY");
        return problem;
    }

    @ExceptionHandler(DuplicateProductException.class)
    ProblemDetail handleDuplicateProduct(DuplicateProductException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Duplicate product");
        problem.setProperty("code", "DUPLICATE_PRODUCT");
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException exception) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Idempotency conflict");
        problem.setProperty("code", "IDEMPOTENCY_KEY_REUSED");
        return problem;
    }

}
