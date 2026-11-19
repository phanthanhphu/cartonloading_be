package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.security.AccessControl;
import jakarta.validation.Valid;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.dto.admin.FactoryRequest;
import org.bsl.cartonloading.dto.admin.FactoryStatusRequest;
import org.bsl.cartonloading.model.Factory;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.springframework.security.access.AccessDeniedException;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/factories")
public class FactoryController {
    private final FactoryCatalogService service;
    private final org.bsl.cartonloading.service.FactoryAccessQueryService query;
    public FactoryController(FactoryCatalogService service, org.bsl.cartonloading.service.FactoryAccessQueryService query) {
        this.service = service;
        this.query = query;
    }
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Factory> list(@RequestParam(defaultValue = AppConstants.Buyer.LULULEMON) String buyerKey,
                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        return query.list(buyerKey, includeInactive);
    }
    @PostMapping
    @PreAuthorize("@accessControl.isAdmin()")
    public Factory create(@Valid @RequestBody FactoryRequest request) { return service.create(request); }
    @PutMapping("/{id}")
    @PreAuthorize("@accessControl.isAdmin()")
    public Factory update(@PathVariable String id, @Valid @RequestBody FactoryRequest request) { return service.update(id, request); }
    @PatchMapping("/{id}/status")
    @PreAuthorize("@accessControl.isAdmin()")
    public Factory updateStatus(@PathVariable String id, @Valid @RequestBody FactoryStatusRequest request) {
        return service.updateStatus(id, request.active());
    }
    @DeleteMapping("/{id}")
    @PreAuthorize("@accessControl.isAdmin()")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) { service.delete(id); }
}
