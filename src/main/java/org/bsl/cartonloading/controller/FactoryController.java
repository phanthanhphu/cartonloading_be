package org.bsl.cartonloading.controller;

import jakarta.validation.Valid;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.dto.admin.FactoryRequest;
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
    private final org.bsl.cartonloading.security.AccessControl accessControl;
    private final BuyerFactoryAccessService access;
    public FactoryController(FactoryCatalogService service, org.bsl.cartonloading.security.AccessControl accessControl, BuyerFactoryAccessService access) { this.service = service; this.accessControl = accessControl; this.access = access; }
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<Factory> list(@RequestParam(defaultValue = AppConstants.Buyer.LULULEMON) String buyerKey,
                              @RequestParam(defaultValue = "false") boolean includeInactive) {
        // Only Admin may inspect inactive factories.
        if (!accessControl.canAccessBuyer(buyerKey)) throw new AccessDeniedException("Buyer access denied");
        if (accessControl.isAdmin()) return service.list(buyerKey, includeInactive);
        return service.list(buyerKey, false).stream()
                .filter(factory -> access.canAccessAssignedFactory(factory.getCode())
                        || (access.currentUser().canWeightCheck() && access.canAccessWeighingFactory(factory.getCode()))).toList();
    }
    @PostMapping
    @PreAuthorize("@accessControl.isAdmin()")
    public Factory create(@Valid @RequestBody FactoryRequest request) { return service.create(request); }
    @PutMapping("/{id}")
    @PreAuthorize("@accessControl.isAdmin()")
    public Factory update(@PathVariable String id, @Valid @RequestBody FactoryRequest request) { return service.update(id, request); }
}
