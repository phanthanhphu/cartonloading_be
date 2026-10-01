package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.dto.admin.BuyerAdminRequest;
import org.bsl.cartonloading.model.Buyer;
import org.bsl.cartonloading.service.BuyerService;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers")
public class BuyerController {
    private final BuyerService service;
    public BuyerController(BuyerService service) { this.service = service; }

    @GetMapping("/login-options") public List<Buyer> loginOptions() { return service.loginOptions(); }
    @GetMapping("/accessible") public List<Buyer> accessible() { return service.accessible(); }

    @PreAuthorize("@accessControl.isAdmin()")
    @GetMapping public Page<Buyer> list(@RequestParam(required = false) String buyerKey,
            @RequestParam(required = false) String buyerName,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) { return service.list(buyerKey, buyerName, description, active, page, size); }

    @PreAuthorize("@accessControl.isAdmin()")
    @PostMapping public Buyer create(@Valid @RequestBody BuyerAdminRequest request) { return service.create(request); }
    @PreAuthorize("@accessControl.isAdmin()")
    @PutMapping("/{id}") public Buyer update(@PathVariable String id, @Valid @RequestBody BuyerAdminRequest request) { return service.update(id, request); }
    @PreAuthorize("@accessControl.isAdmin()")
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable String id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
