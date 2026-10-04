package com.example.shop.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.shop.order.catalog.CatalogClient;
import com.example.shop.order.domain.Order;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OrderPlacementServiceTest {

    @Mock CatalogClient catalog;
    @Mock OrderService orders;

    @Test
    void everyLineIsPricedFromTheCatalog() {
        when(catalog.priceOf("A")).thenReturn(new BigDecimal("10.00"));
        when(catalog.priceOf("B")).thenReturn(new BigDecimal("2.50"));
        var request = new PlaceOrderRequest("cust", List.of(new PlaceOrderRequest.Item("A", 2), new PlaceOrderRequest.Item("B", 4)));

        new OrderPlacementService(catalog, orders).place(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Order.ItemSpec>> lines = ArgumentCaptor.forClass(List.class);
        verify(orders).create(eq("cust"), lines.capture());
        assertThat(lines.getValue()).containsExactly(
                new Order.ItemSpec("A", 2, new BigDecimal("10.00")), new Order.ItemSpec("B", 4, new BigDecimal("2.50")));
    }

    @Test
    void sameSkuOnTwoLinesIsLookedUpOnce() {
        when(catalog.priceOf("A")).thenReturn(BigDecimal.ONE);
        var request = new PlaceOrderRequest("cust", List.of(new PlaceOrderRequest.Item("A", 1), new PlaceOrderRequest.Item("A", 2)));

        new OrderPlacementService(catalog, orders).place(request);

        verify(catalog, times(1)).priceOf("A");
    }

    @Test
    void unknownSkuIsRejectedBeforeAnythingIsPersisted() {
        when(catalog.priceOf("NOPE")).thenReturn(null);
        var request = new PlaceOrderRequest("cust", List.of(new PlaceOrderRequest.Item("NOPE", 1)));

        assertThatThrownBy(() -> new OrderPlacementService(catalog, orders).place(request))
                .isInstanceOf(UnknownProductException.class).hasMessageContaining("NOPE");
        verify(orders, never()).create(any(), anyList());
    }

    @Test
    void catalogOutageSurfacesAndNothingIsPersisted() {
        when(catalog.priceOf("A")).thenThrow(new CatalogUnavailableException("down", null));
        var request = new PlaceOrderRequest("cust", List.of(new PlaceOrderRequest.Item("A", 1)));

        assertThatThrownBy(() -> new OrderPlacementService(catalog, orders).place(request))
                .isInstanceOf(CatalogUnavailableException.class);
        verify(orders, never()).create(any(), anyList());
    }
}
