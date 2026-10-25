package com.example.shop.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.example.shop.catalog.config.SecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Provider side of contracts/catalog-get-product.v1.json: what the order service relies on is exactly what this service
 * returns. If the catalog renames or retypes a field, this fails here, before the order service breaks in production.
 */
@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class CatalogContractProviderTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean ProductRepository products;
    @MockitoBean JwtDecoder jwtDecoder;

    @Test
    void everyInteractionOfTheContractIsHonoured() throws Exception {
        JsonNode contract = mapper.readTree(Files.readString(Path.of(System.getProperty("contracts.dir"), "catalog-get-product.v1.json")));
        assertThat(contract.get("interactions")).isNotEmpty();

        for (JsonNode i : contract.get("interactions")) {
            JsonNode state = i.get("providerState");
            if (state != null && !state.isNull()) {
                when(products.findById(state.get("sku").asText())).thenReturn(Optional.of(
                        new Product(state.get("sku").asText(), state.get("name").asText(), new BigDecimal(state.get("price").asText()))));
            } else {
                when(products.findById(i.get("request").get("path").asText().replaceAll(".*/", ""))).thenReturn(Optional.empty());
            }
            // the order service calls with a SERVICE token
            var result = mvc.perform(get(i.get("request").get("path").asText())
                    .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SERVICE")))).andReturn().getResponse();

            JsonNode expected = i.get("response");
            assertThat(result.getStatus()).as(i.get("description").asText()).isEqualTo(expected.get("status").asInt());
            assertThat(result.getContentType()).startsWith(expected.get("contentType").asText());
            if (expected.has("schema")) {
                var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                        .getSchema(expected.get("schema"));
                assertThat(schema.validate(mapper.readTree(result.getContentAsString())))
                        .as("response body must satisfy the contract schema: " + result.getContentAsString()).isEmpty();
            }
        }
    }
}
