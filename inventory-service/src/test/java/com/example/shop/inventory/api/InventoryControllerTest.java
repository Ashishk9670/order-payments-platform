package com.example.shop.inventory.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.shop.inventory.domain.InventoryItem;
import com.example.shop.inventory.domain.InventoryItemRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean InventoryItemRepository items;

    @Test
    void returnsStockLevels() throws Exception {
        when(items.findById("A")).thenReturn(Optional.of(new InventoryItem("A", 7)));

        mvc.perform(get("/api/v1/inventory/A")).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(7)).andExpect(jsonPath("$.reserved").value(0));
    }

    @Test
    void unknownSkuIs404ProblemJson() throws Exception {
        when(items.findById("X")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/inventory/X")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Unknown SKU X"));
    }

    @Test
    void restockOfAnExistingSkuIncrementsIt() throws Exception {
        when(items.addStock("A", 5)).thenReturn(1);
        when(items.findById("A")).thenReturn(Optional.of(new InventoryItem("A", 12)));

        mvc.perform(post("/api/v1/inventory/A/restock").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(12));
        verify(items, never()).save(any());
    }

    @Test
    void restockOfANewSkuCreatesIt() throws Exception {
        when(items.addStock(eq("N"), anyInt())).thenReturn(0);
        when(items.findById("N")).thenReturn(Optional.of(new InventoryItem("N", 3)));

        mvc.perform(post("/api/v1/inventory/N/restock").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":3}"))
                .andExpect(status().isOk());
        verify(items).save(any(InventoryItem.class));
    }

    @Test
    void restockRejectsNonPositiveQuantity() throws Exception {
        mvc.perform(post("/api/v1/inventory/A/restock").contentType(MediaType.APPLICATION_JSON).content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest());
        verify(items, never()).addStock(any(), anyInt());
    }
}
