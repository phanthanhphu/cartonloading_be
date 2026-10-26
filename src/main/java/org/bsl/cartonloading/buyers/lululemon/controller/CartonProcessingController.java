package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.CartonProcessingIdentityRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.CartonProcessingWeightRequest;
import org.bsl.cartonloading.buyers.lululemon.service.CartonProcessingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Direct operator station: no PO self-pick or Shipping Schedule dispatch. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/carton-processing")
public class CartonProcessingController {
    private final CartonProcessingService service;
    private final org.bsl.cartonloading.buyers.lululemon.service.FactoryPackingImportService imports;
    public CartonProcessingController(CartonProcessingService service,
                                      org.bsl.cartonloading.buyers.lululemon.service.FactoryPackingImportService imports) {
        this.service = service; this.imports = imports;
    }

    @GetMapping("/imports")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.isAdmin())")
    public java.util.List<org.bsl.cartonloading.buyers.lululemon.model.PackingImportBatch> listImports(
            @PathVariable String buyer, @RequestParam String factoryCode) {
        return imports.list(buyer, factoryCode);
    }

    @PostMapping(value = "/imports", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canManageSales() or @accessControl.canAssignBarcode() or @accessControl.isAdmin())")
    public org.bsl.cartonloading.buyers.lululemon.model.PackingImportBatch importPacking(
            @PathVariable String buyer, @RequestParam String orderId, @RequestParam String factoryCode,
            @RequestPart("file") org.springframework.web.multipart.MultipartFile file) {
        return imports.importWorkbook(buyer, orderId, factoryCode, file);
    }

    @GetMapping("/summary")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.canAssignBarcode() or @accessControl.canManageSales() or @accessControl.isAdmin())")
    public CartonProcessingService.StationSummary summary(@PathVariable String buyer,
                                                            @RequestParam(required = false) String factoryCode,
                                                            @RequestParam(required = false) String orderId,
                                                            @RequestParam(required = false) String poId) {
        return service.summary(buyer, factoryCode, orderId, poId);
    }

    @PostMapping("/identify")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.canAssignBarcode() or @accessControl.canManageSales() or @accessControl.isAdmin())")
    public CartonProcessingService.IdentifiedCarton identify(@PathVariable String buyer,
                                                               @RequestBody CartonProcessingIdentityRequest request) {
        return service.identify(buyer, request);
    }

    @PostMapping("/manual-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.WeightResult saveManualWeight(@PathVariable String buyer,
                                                                  @RequestBody CartonProcessingWeightRequest request) {
        return service.manualWeight(buyer, request);
    }
    public record AutoWeightRequest(String sscc, String stationCode, String eventId) { }

    public record ArmWeightRequest(String sscc, String stationCode) { }

    @PostMapping("/auto-weight/arm")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public void armAutoWeight(@PathVariable String buyer, @RequestBody ArmWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("Station and SSCC required");
        service.armAutoWeight(buyer, request.sscc(), request.stationCode());
    }

    @PostMapping("/auto-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.WeightResult autoWeight(@PathVariable String buyer,
            @RequestBody AutoWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("Scale event is required");
        return service.autoWeight(buyer, request.sscc(), request.stationCode(), request.eventId());
    }

    public record RecallFailedRequest(String orderId, String poId, String cartonId, String reason) { }

    /** Operator recall, scoped to a completed FINAL FAILED carton in this PO. */
    @PostMapping("/recall-failed")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public CartonProcessingService.IdentifiedCarton recallFailed(
            @PathVariable String buyer, @RequestBody RecallFailedRequest request) {
        if (request == null) throw new IllegalArgumentException("Recall request is required.");
        return service.recallFailed(buyer, request.orderId(), request.poId(), request.cartonId(), request.reason());
    }

    public record ReopenWeightRequest(String sscc, String reason) { }

    @PostMapping("/reopen-weight")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public org.bsl.cartonloading.buyers.lululemon.model.Carton reopenWeight(
            @PathVariable String buyer, @RequestBody ReopenWeightRequest request) {
        if (request == null) throw new IllegalArgumentException("SSCC and reason are required.");
        return service.reopenManualWeight(buyer, request.sscc(), request.reason());
    }

}
