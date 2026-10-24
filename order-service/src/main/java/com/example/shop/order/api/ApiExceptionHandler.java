package com.example.shop.order.api;

import com.example.shop.order.catalog.CatalogUnavailableException;
import com.example.shop.order.redis.RateLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** RFC 7807 problem+json for every error. The parent class already maps validation errors to 400. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail notFound(OrderNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail conflict(IdempotencyConflictException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(UnknownProductException.class)
    ProblemDetail unknownProduct(UnknownProductException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(CatalogUnavailableException.class)
    ProblemDetail catalogDown(CatalogUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "Pricing is temporarily unavailable, retry shortly");
    }

    @ExceptionHandler({OrderStateConflictException.class, org.springframework.orm.ObjectOptimisticLockingFailureException.class})
    ProblemDetail stateConflict(RuntimeException e) {
        String detail = e instanceof OrderStateConflictException ? e.getMessage() : "The order was modified concurrently, retry";
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
    }

    @ExceptionHandler(com.example.shop.order.redis.IdempotencyUnavailableException.class)
    ProblemDetail idempotencyUnavailable(com.example.shop.order.redis.IdempotencyUnavailableException e) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Idempotency cannot be guaranteed right now; retry shortly (or send the request without an Idempotency-Key at your own risk of a duplicate)");
        return problem;
    }

    /** The database cannot be reached (or a connection could not be obtained in time): a temporary condition, so say so. */
    @ExceptionHandler({org.springframework.transaction.CannotCreateTransactionException.class,
            org.springframework.dao.DataAccessResourceFailureException.class,
            org.springframework.jdbc.CannotGetJdbcConnectionException.class})
    org.springframework.http.ResponseEntity<ProblemDetail> databaseUnavailable(RuntimeException e) {
        return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "The service is temporarily unavailable, retry shortly"));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ProblemDetail tooMany(RateLimitExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, e.getMessage());
    }
}
