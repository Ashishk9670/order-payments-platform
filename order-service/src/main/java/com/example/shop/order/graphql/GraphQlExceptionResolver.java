package com.example.shop.order.graphql;

import com.example.shop.order.api.CatalogUnavailableException;
import com.example.shop.order.api.OrderNotFoundException;
import com.example.shop.order.api.OrderStateConflictException;
import com.example.shop.order.api.UnknownProductException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.stereotype.Component;

/** Domain errors become typed GraphQL errors instead of an opaque INTERNAL_ERROR. */
@Component
class GraphQlExceptionResolver extends DataFetcherExceptionResolverAdapter {

    @Override
    protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
        ErrorType type;
        String message = ex.getMessage();
        if (ex instanceof OrderNotFoundException) {
            type = ErrorType.NOT_FOUND;
        } else if (ex instanceof UnknownProductException || ex instanceof ConstraintViolationException) {
            type = ErrorType.BAD_REQUEST;
        } else if (ex instanceof OrderStateConflictException) {
            type = ErrorType.BAD_REQUEST;
        } else if (ex instanceof AccessDeniedException) {
            type = ErrorType.FORBIDDEN;
            message = "You can only access your own orders";
        } else if (ex instanceof CatalogUnavailableException) {
            type = ErrorType.INTERNAL_ERROR;
            message = "Pricing is temporarily unavailable, retry shortly";
        } else {
            return null;   // fall through to the default handling (masked as INTERNAL_ERROR)
        }
        return GraphqlErrorBuilder.newError(env).errorType(type).message(message).build();
    }
}
