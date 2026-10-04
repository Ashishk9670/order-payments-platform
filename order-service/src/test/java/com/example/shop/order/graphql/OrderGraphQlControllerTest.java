package com.example.shop.order.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.order.api.OrderNotFoundException;
import com.example.shop.order.api.OrderPlacementService;
import com.example.shop.order.api.OrderService;
import com.example.shop.order.api.OrderStateConflictException;
import com.example.shop.order.api.OrderView;
import com.example.shop.order.api.PlaceOrderRequest;
import com.example.shop.order.api.UnknownProductException;
import com.example.shop.order.domain.Order;
import com.example.shop.order.domain.OrderRepository;
import com.example.shop.order.security.CurrentUser;
import org.springframework.security.access.AccessDeniedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.graphql.GraphQlTest;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Sinks;

/** GraphQL layer in isolation: schema wiring, argument binding, validation, error mapping, batching, subscription. */
@GraphQlTest(OrderGraphQlController.class)
class OrderGraphQlControllerTest {

    @Autowired GraphQlTester graphQl;
    @MockitoBean OrderRepository orders;
    @MockitoBean OrderPlacementService placement;
    @MockitoBean OrderService orderService;
    @MockitoBean OrderUpdates updates;
    @MockitoBean CurrentUser access;

    Order order;

    @BeforeEach
    void setUp() {
        order = Order.create("cust-1", List.of(new Order.ItemSpec("SKU-1", 2, new BigDecimal("5.00"))));
        when(orders.findById(order.getId())).thenReturn(Optional.of(order));
        when(access.canAccess(any())).thenReturn(true);
    }

    private OrderView viewOf(Order o) {
        return OrderView.from(o);
    }

    @Test
    void queryReturnsOrder() {
        graphQl.document("{ order(id: \"" + order.getId() + "\") { id customerId status totalAmount } }")
                .execute()
                .path("order.status").entity(String.class).isEqualTo("PENDING")
                .path("order.customerId").entity(String.class).isEqualTo("cust-1")
                .path("order.totalAmount").entity(Double.class).isEqualTo(10.0);
    }

    @Test
    void unknownOrderIsNull() {
        var id = UUID.randomUUID();
        when(orders.findById(id)).thenReturn(Optional.empty());

        graphQl.document("{ order(id: \"" + id + "\") { id } }").execute().path("order").valueIsNull();
    }

    @Test
    void itemsOfManyOrdersAreLoadedWithOneBatchedQuery() {
        var second = Order.create("cust-1", List.of(new Order.ItemSpec("SKU-9", 1, BigDecimal.ONE)));
        when(orders.findByCustomerIdOrderByCreatedAtDesc(eq("cust-1"), any())).thenReturn(List.of(order, second));
        when(orders.findItemRows(any())).thenReturn(List.of(
                new OrderRepository.ItemRow(order.getId(), "SKU-1", 2, new BigDecimal("5.00")),
                new OrderRepository.ItemRow(second.getId(), "SKU-9", 1, BigDecimal.ONE)));

        graphQl.document("{ ordersByCustomer(customerId: \"cust-1\") { id items { sku quantity } } }")
                .execute()
                .path("ordersByCustomer[0].items[0].sku").entity(String.class).isEqualTo("SKU-1")
                .path("ordersByCustomer[1].items[0].sku").entity(String.class).isEqualTo("SKU-9");
        verify(orders, org.mockito.Mockito.times(1)).findItemRows(any());
    }

    @Test
    void placeOrderMutationDelegatesToTheSameServiceAsRest() {
        when(placement.place(any())).thenReturn(viewOf(order));

        graphQl.document("""
                        mutation { placeOrder(input: { customerId: "cust-1", items: [{ sku: "SKU-1", quantity: 2 }] }) { id status } }""")
                .execute()
                .path("placeOrder.status").entity(String.class).isEqualTo("PENDING");

        var captor = ArgumentCaptor.forClass(PlaceOrderRequest.class);
        verify(placement).place(captor.capture());
        assertThat(captor.getValue().customerId()).isEqualTo("cust-1");
        assertThat(captor.getValue().items()).containsExactly(new PlaceOrderRequest.Item("SKU-1", 2));
    }

    @Test
    void placeOrderValidatesInputLikeRest() {
        graphQl.document("""
                        mutation { placeOrder(input: { customerId: "", items: [{ sku: "S", quantity: 0 }] }) { id } }""")
                .execute()
                .errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        verify(placement, never()).place(any());
    }

    @Test
    void unknownProductBecomesABadRequestError() {
        when(placement.place(any())).thenThrow(new UnknownProductException("NOPE"));

        graphQl.document("""
                        mutation { placeOrder(input: { customerId: "c", items: [{ sku: "NOPE", quantity: 1 }] }) { id } }""")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST && "Unknown product NOPE".equals(e.getMessage()))
                .verify();
    }

    @Test
    void cancelOrderConflictAndNotFoundAreTypedErrors() {
        var conflict = order.getId();            // exists and is visible, but is not CONFIRMED
        var missing = UUID.randomUUID();
        when(orders.findById(missing)).thenReturn(Optional.empty());
        when(orderService.requestCancellation(eq(conflict), any())).thenThrow(new OrderStateConflictException("Only confirmed orders can be cancelled; order is PENDING"));

        graphQl.document("mutation { cancelOrder(id: \"" + conflict + "\") { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        graphQl.document("mutation { cancelOrder(id: \"" + missing + "\") { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.NOT_FOUND).verify();
    }

    @Test
    void subscriptionPushesCurrentStatusThenChangesAndCompletesAtTerminalState() {
        Sinks.Many<UUID> sink = Sinks.many().multicast().directBestEffort();
        when(updates.changesOf(order.getId())).thenReturn(sink.asFlux());

        var statuses = graphQl.document("subscription { orderStatusChanged(id: \"" + order.getId() + "\") { status } }")
                .executeSubscription()
                .toFlux("orderStatusChanged.status", String.class)
                .collectList().toFuture();

        await().atMost(Duration.ofSeconds(5)).until(() -> sink.currentSubscriberCount() > 0);
        order.markInventoryReserved();
        sink.tryEmitNext(order.getId());
        await().pollDelay(Duration.ofMillis(150)).atMost(Duration.ofSeconds(5)).until(() -> true);
        order.confirm();
        sink.tryEmitNext(order.getId());

        var seen = await().atMost(Duration.ofSeconds(5)).until(() -> statuses.isDone() ? statuses.get() : null, s -> s != null);
        assertThat(seen.get(0)).isEqualTo("PENDING");
        assertThat(seen.get(seen.size() - 1)).isEqualTo("CONFIRMED");
    }

    @Test
    void anotherCustomersOrderIsInvisible() {
        when(access.canAccess("cust-1")).thenReturn(false);

        graphQl.document("{ order(id: \"" + order.getId() + "\") { id } }").execute().path("order").valueIsNull();
    }

    @Test
    void listingAnotherCustomersOrdersIsForbidden() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("nope")).when(access).requireAccess("cust-1");

        graphQl.document("{ ordersByCustomer(customerId: \"cust-1\") { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        verify(orders, never()).findByCustomerIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void placingAnOrderForAnotherCustomerIsForbidden() {
        org.mockito.Mockito.doThrow(new AccessDeniedException("nope")).when(access).requireAccess("victim");

        graphQl.document("""
                        mutation { placeOrder(input: { customerId: "victim", items: [{ sku: "S", quantity: 1 }] }) { id } }""")
                .execute().errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        verify(placement, never()).place(any());
    }

    @Test
    void cancellingAnotherCustomersOrderLooksLikeNotFound() {
        when(access.canAccess("cust-1")).thenReturn(false);

        graphQl.document("mutation { cancelOrder(id: \"" + order.getId() + "\") { id } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.NOT_FOUND).verify();
        verify(orderService, never()).requestCancellation(any(), any());
    }

    @Test
    void subscribingToAnotherCustomersOrderIsRejectedUpFront() {
        when(access.canAccess("cust-1")).thenReturn(false);

        graphQl.document("subscription { orderStatusChanged(id: \"" + order.getId() + "\") { status } }")
                .executeSubscription().toFlux("orderStatusChanged.status", String.class)
                .collectList().as(m -> m).onErrorResume(e -> reactor.core.publisher.Mono.empty()).block(Duration.ofSeconds(5));
        verify(updates, never()).changesOf(any());
    }
}
