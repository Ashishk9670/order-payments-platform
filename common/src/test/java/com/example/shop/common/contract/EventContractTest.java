package com.example.shop.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.shop.common.events.DomainEvent;
import com.example.shop.common.events.InventoryFailed;
import com.example.shop.common.events.InventoryReserved;
import com.example.shop.common.events.OrderCancellationRequested;
import com.example.shop.common.events.OrderCancelled;
import com.example.shop.common.events.OrderConfirmed;
import com.example.shop.common.events.OrderCreated;
import com.example.shop.common.events.PaymentAuthorized;
import com.example.shop.common.events.PaymentFailed;
import com.example.shop.common.events.PaymentRefundFailed;
import com.example.shop.common.events.PaymentRefunded;
import com.example.shop.common.events.Topics;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

/**
 * Event contracts. The JSON Schemas in src/main/resources/schemas are the published contract of every Kafka message:
 * what the code actually emits must satisfy them, they must not drift from the record definitions, they may only
 * evolve compatibly, and the AsyncAPI document must list exactly the topics the code uses.
 */
class EventContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final UUID ORDER = UUID.randomUUID();

    static Stream<DomainEvent> sampleEvents() {
        return Stream.of(
                OrderCreated.of(ORDER, "cust", List.of(new OrderCreated.Line("SKU-1", 2)), new BigDecimal("12.50")),
                InventoryReserved.of(ORDER, "cust", new BigDecimal("12.50")),
                InventoryFailed.of(ORDER, "no stock"),
                PaymentAuthorized.of(ORDER, UUID.randomUUID()),
                PaymentFailed.of(ORDER, "declined"),
                OrderConfirmed.of(ORDER, "cust"),
                OrderCancelled.of(ORDER, "cust", "because"),
                OrderCancellationRequested.of(ORDER, "changed my mind"),
                PaymentRefunded.of(ORDER, UUID.randomUUID()),
                PaymentRefundFailed.of(ORDER, "psp down"));
    }

    private static JsonNode load(String resource) throws Exception {
        try (InputStream in = EventContractTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("missing " + resource).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleEvents")
    void whatTheCodeEmitsSatisfiesThePublishedSchema(DomainEvent event) throws Exception {
        String name = event.getClass().getSimpleName();
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(load("/schemas/" + name + ".v1.json"));

        var errors = schema.validate(MAPPER.readTree(MAPPER.writeValueAsString(event)));

        assertThat(errors).as(name + " violates its schema").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleEvents")
    void schemaPropertiesMatchTheRecordFieldsExactly(DomainEvent event) throws Exception {
        String name = event.getClass().getSimpleName();
        JsonNode schema = load("/schemas/" + name + ".v1.json");
        Set<String> recordFields = new LinkedHashSet<>();
        for (Field f : event.getClass().getDeclaredFields()) {
            if (!f.isSynthetic() && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                recordFields.add(f.getName());
            }
        }
        Set<String> schemaProps = new LinkedHashSet<>();
        schema.get("properties").fieldNames().forEachRemaining(schemaProps::add);
        Set<String> required = new LinkedHashSet<>();
        schema.get("required").forEach(n -> required.add(n.asText()));

        assertThat(schemaProps).as(name + ": add/remove the field in the schema too").isEqualTo(recordFields);
        assertThat(required).isEqualTo(recordFields);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sampleEvents")
    void schemaOnlyEvolvesCompatiblyWithTheReleasedBaseline(DomainEvent event) throws Exception {
        String name = event.getClass().getSimpleName();
        var problems = SchemaCompatibility.breakingChanges(load("/schema-baseline/" + name + ".v1.json"), load("/schemas/" + name + ".v1.json"));

        assertThat(problems).as(name + " would break consumers; publish it as a NEW versioned topic instead").isEmpty();
    }

    @Test
    void anEventMissingARequiredFieldIsRejectedByTheSchema() throws Exception {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(load("/schemas/PaymentFailed.v1.json"));
        var broken = MAPPER.readTree("{\"eventId\":\"" + UUID.randomUUID() + "\",\"orderId\":\"" + ORDER + "\"}");

        assertThat(schema.validate(broken)).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncApiDocumentListsExactlyTheTopicsTheCodeUses() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = new java.io.FileInputStream(java.nio.file.Path.of("..", "docs", "asyncapi.yaml").toFile())) {
            doc = new Yaml().load(in);
        }
        Set<String> documented = new LinkedHashSet<>();
        ((Map<String, Map<String, Object>>) doc.get("channels")).values().forEach(c -> documented.add((String) c.get("address")));

        Set<String> inCode = new LinkedHashSet<>();
        for (Field f : Topics.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isPublic(f.getModifiers()) && f.getType() == String.class) {
                inCode.add((String) f.get(null));
            }
        }
        assertThat(documented).as("docs/asyncapi.yaml out of sync with Topics").isEqualTo(inCode);
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncApiMessagesMatchTheJsonSchemaFields() throws Exception {
        Map<String, Object> doc;
        try (InputStream in = new java.io.FileInputStream(java.nio.file.Path.of("..", "docs", "asyncapi.yaml").toFile())) {
            doc = new Yaml().load(in);
        }
        var messages = (Map<String, Map<String, Object>>) ((Map<String, Object>) doc.get("components")).get("messages");
        List<String> drift = new ArrayList<>();
        for (var entry : messages.entrySet()) {
            var payload = (Map<String, Object>) entry.getValue().get("payload");
            Set<String> documented = new LinkedHashSet<>(((Map<String, Object>) payload.get("properties")).keySet());
            Set<String> real = new LinkedHashSet<>();
            load("/schemas/" + entry.getKey() + ".v1.json").get("properties").fieldNames().forEachRemaining(real::add);
            if (!documented.equals(real)) {
                drift.add(entry.getKey() + ": asyncapi=" + documented + " schema=" + real);
            }
        }
        assertThat(drift).as("docs/asyncapi.yaml message payloads drifted from schemas/").isEmpty();
    }
}
