package com.example.shop.common.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/** Proves the compatibility checker itself catches breaking changes (otherwise the contract test guards nothing). */
class SchemaCompatibilityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    private static final String BASE = """
            {"type":"object","properties":{"id":{"type":"string","format":"uuid"},"qty":{"type":"integer"},
             "lines":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}},"required":["sku"]}}},
             "required":["id","qty"]}""";

    @Test
    void identicalSchemaIsCompatible() throws Exception {
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(BASE))).isEmpty();
    }

    @Test
    void addingAnOptionalPropertyIsCompatible() throws Exception {
        var candidate = BASE.replace("\"qty\":{\"type\":\"integer\"}", "\"qty\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).isEmpty();
    }

    @Test
    void removingAPropertyIsBreaking() throws Exception {
        var candidate = BASE.replace("\"qty\":{\"type\":\"integer\"},", "");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("qty") && p.contains("removed"));
    }

    @Test
    void changingATypeIsBreaking() throws Exception {
        var candidate = BASE.replace("\"qty\":{\"type\":\"integer\"}", "\"qty\":{\"type\":\"string\"}");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("$.qty") && p.contains("type changed"));
    }

    @Test
    void changingAFormatIsBreaking() throws Exception {
        var candidate = BASE.replace("\"format\":\"uuid\"", "\"format\":\"date-time\"");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("format changed"));
    }

    @Test
    void addingARequiredPropertyIsBreaking() throws Exception {
        var candidate = BASE.replace("\"qty\":{\"type\":\"integer\"}", "\"qty\":{\"type\":\"integer\"},\"note\":{\"type\":\"string\"}")
                .replace("\"required\":[\"id\",\"qty\"]", "\"required\":[\"id\",\"qty\",\"note\"]");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("new required property 'note'"));
    }

    @Test
    void droppingARequirementIsBreaking() throws Exception {
        var candidate = BASE.replace("\"required\":[\"id\",\"qty\"]", "\"required\":[\"id\"]");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("no longer required"));
    }

    @Test
    void breakingChangesInsideArrayItemsAreFound() throws Exception {
        var candidate = BASE.replace("\"sku\":{\"type\":\"string\"}", "\"sku\":{\"type\":\"integer\"}");
        assertThat(SchemaCompatibility.breakingChanges(json(BASE), json(candidate))).anyMatch(p -> p.contains("[].sku"));
    }
}
