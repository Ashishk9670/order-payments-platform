package com.example.shop.order.catalog;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-through of the catalog so the storefront only needs to talk to one service. */
@RestController
@RequestMapping("/api/v1/products")
class ProductsController {

    private final CatalogClient catalog;

    ProductsController(CatalogClient catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    List<CatalogClient.ProductDto> list() {
        return catalog.list();
    }
}
