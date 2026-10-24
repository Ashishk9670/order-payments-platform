package com.example.shop.catalog;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.catalog.config.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import(SecurityConfig.class)
class ProductControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder jwtDecoder;   // the real decoder checks signatures; these tests inject an already-authenticated JWT

    private static RequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("ops")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor service() {
        return jwt().jwt(j -> j.subject("order-service")).authorities(new SimpleGrantedAuthority("ROLE_SERVICE"));
    }

    private static RequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("alice")).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    @MockitoBean ProductRepository products;

    @Test
    void getReturnsProduct() throws Exception {
        when(products.findById("A")).thenReturn(Optional.of(new Product("A", "Alpha", new BigDecimal("1.50"))));

        mvc.perform(get("/api/v1/products/A").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(1.5))
                .andExpect(jsonPath("$.name").value("Alpha"));
    }

    @Test
    void unknownSkuIs404() throws Exception {
        when(products.findById("X")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/products/X").with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void listIsReturned() throws Exception {
        when(products.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(new Product("A", "Alpha", BigDecimal.ONE)));

        mvc.perform(get("/api/v1/products").with(admin())).andExpect(status().isOk()).andExpect(jsonPath("$[0].sku").value("A"));
    }

    @Test
    void putCreatesWith201() throws Exception {
        when(products.findById("N")).thenReturn(Optional.empty());
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/v1/products/N").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New\",\"price\":5.00}"))
                .andExpect(status().isCreated());
    }

    @Test
    void putUpdatesExistingWith200() throws Exception {
        var p = new Product("E", "Old", BigDecimal.ONE);
        when(products.findById("E")).thenReturn(Optional.of(p));

        mvc.perform(put("/api/v1/products/E").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"price\":9.99}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
        verify(products, never()).save(any());
    }

    @Test
    void putRejectsNonPositivePrice() throws Exception {
        mvc.perform(put("/api/v1/products/N").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New\",\"price\":0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putRejectsBlankName() throws Exception {
        mvc.perform(put("/api/v1/products/N").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"price\":1}"))
                .andExpect(status().isBadRequest());
    }

    // ---- who may call this internal API ---------------------------------------------------------------------------

    @Test
    void noTokenIs401ProblemJson() throws Exception {
        mvc.perform(get("/api/v1/products/A")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Type", "application/problem+json"));
        mvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
    }

    @Test
    void servicesMayReadPricesButNotChangeThem() throws Exception {
        when(products.findById("A")).thenReturn(Optional.of(new Product("A", "Alpha", new BigDecimal("1.50"))));

        mvc.perform(get("/api/v1/products/A").with(service())).andExpect(status().isOk());
        mvc.perform(put("/api/v1/products/A").with(service()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"price\":0.01}")).andExpect(status().isForbidden());
        verify(products, never()).save(any());
    }

    @Test
    void aCustomerCanNeitherReadNorChangeTheCatalogDirectly() throws Exception {
        mvc.perform(get("/api/v1/products/A").with(customer())).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/products/A").with(customer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"price\":0.01}")).andExpect(status().isForbidden());
    }

    @Test
    void onlyAnAdminChangesPrices() throws Exception {
        when(products.findById("N")).thenReturn(Optional.empty());
        when(products.save(any(Product.class))).thenAnswer(i -> i.getArgument(0));

        mvc.perform(put("/api/v1/products/N").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New\",\"price\":5.00}")).andExpect(status().isCreated());
    }
}
