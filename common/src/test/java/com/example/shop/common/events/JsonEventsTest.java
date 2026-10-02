package com.example.shop.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonEventsTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    void orderCreatedSurvivesJsonRoundTrip() throws Exception {
        var original = OrderCreated.of(UUID.randomUUID(), "cust-1",
                List.of(new OrderCreated.Line("SKU-1", 2), new OrderCreated.Line("SKU-2", 1)), new BigDecimal("59.98"));

        var copy = JsonEvents.read(mapper, mapper.writeValueAsString(original), OrderCreated.class);

        assertThat(copy).isEqualTo(original);
    }

    @Test
    void malformedPayloadIsNonRetryableIllegalArgument() {
        assertThatThrownBy(() -> JsonEvents.read(mapper, "{not json", PaymentFailed.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PaymentFailed");
    }

    @Test
    void eventTypeIsSimpleClassName() {
        assertThat(InventoryFailed.of(UUID.randomUUID(), "x").eventType()).isEqualTo("InventoryFailed");
    }
}
