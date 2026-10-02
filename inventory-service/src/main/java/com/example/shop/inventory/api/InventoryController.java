package com.example.shop.inventory.api;

import com.example.shop.inventory.domain.InventoryItem;
import com.example.shop.inventory.domain.InventoryItemRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inventory")
public class InventoryController {

    public record StockView(String sku, int available, int reserved) {
    }

    public record RestockRequest(@Min(1) int quantity) {
    }

    private final InventoryItemRepository items;

    public InventoryController(InventoryItemRepository items) {
        this.items = items;
    }

    @GetMapping("/{sku}")
    public ResponseEntity<?> get(@PathVariable String sku) {
        return items.findById(sku).<ResponseEntity<?>>map(i -> ResponseEntity.ok(view(i)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Unknown SKU " + sku)));
    }

    @PostMapping("/{sku}/restock")
    @Transactional
    public ResponseEntity<?> restock(@PathVariable String sku, @Valid @RequestBody RestockRequest request) {
        if (items.addStock(sku, request.quantity()) == 0) {
            items.save(new InventoryItem(sku, request.quantity()));
        }
        return get(sku);
    }

    private static StockView view(InventoryItem i) {
        return new StockView(i.getSku(), i.getAvailable(), i.getReserved());
    }
}
