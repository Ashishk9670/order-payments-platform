package com.example.shop.catalog;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    public record ProductView(String sku, String name, BigDecimal price) {
        static ProductView from(Product p) {
            return new ProductView(p.getSku(), p.getName(), p.getPrice());
        }
    }

    public record UpsertRequest(@NotBlank @Size(max = 200) String name, @NotNull @DecimalMin("0.01") BigDecimal price) {
    }

    private final ProductRepository products;

    public ProductController(ProductRepository products) {
        this.products = products;
    }

    @GetMapping
    public List<ProductView> list() {
        return products.findAll(Sort.by("sku")).stream().map(ProductView::from).toList();
    }

    @GetMapping("/{sku}")
    public ProductView get(@PathVariable String sku) {
        return products.findById(sku).map(ProductView::from).orElseThrow(() -> new ProductNotFoundException(sku));
    }

    /** Idempotent upsert: 201 when created, 200 when updated. */
    @PutMapping("/{sku}")
    @Transactional
    public ResponseEntity<ProductView> upsert(@PathVariable String sku, @Valid @RequestBody UpsertRequest request) {
        var existing = products.findById(sku);
        if (existing.isPresent()) {
            existing.get().update(request.name(), request.price());
            return ResponseEntity.ok(ProductView.from(existing.get()));
        }
        Product created = products.save(new Product(sku, request.name(), request.price()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ProductView.from(created));
    }
}
