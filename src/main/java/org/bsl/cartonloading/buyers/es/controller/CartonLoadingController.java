package org.bsl.cartonloading.buyers.es.controller;

import org.bsl.cartonloading.common.constants.FilterValue;

import org.bsl.cartonloading.buyers.es.dto.carton.*;
import org.bsl.cartonloading.buyers.es.dto.barcode.BarcodeAssignmentPageResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeAssignRequest;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeScanRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.service.CartonLoadingService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/carton-loading")
public class CartonLoadingController {
    private final CartonLoadingService service;

    public CartonLoadingController(CartonLoadingService service) {
        this.service = service;
    }


    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canManageSales()")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/generate")
    public CartonPlanGenerationResult generateCartonsFromWsp(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(defaultValue = "true") boolean replace
    ) {
        return service.generateFromWsp(buyer, orderId, replace);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/cartons")
    public Page<CartonScanTransaction> listCartons(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.listCartons(buyer, orderId, keyword, status, page, size);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer)")
    @GetMapping("/{buyer}/orders/{orderId}/items/{masterLineId}/cartons")
    public List<CartonScanTransaction> listCartonsForItem(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String masterLineId
    ) {
        return service.listCartonsForItem(buyer, orderId, masterLineId);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    @GetMapping("/{buyer}/orders/{orderId}/barcode-assignment/cartons")
    public BarcodeAssignmentPageResponse listCartonsForBarcodeAssignment(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = FilterValue.UNASSIGNED) String assignment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        return service.listCartonsForBarcodeAssignment(buyer, orderId, keyword, assignment, page, size);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    @GetMapping("/{buyer}/orders/{orderId}/barcode-assignment/check/{barcode}")
    public FactoryBarcode checkFactoryBarcodeForAssignment(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String barcode
    ) {
        return service.checkFactoryBarcodeForAssignment(buyer, orderId, barcode);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    @PostMapping("/{buyer}/orders/{orderId}/barcode-assignment")
    public CartonScanTransaction assignFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody FactoryBarcodeAssignRequest request
    ) {
        return service.assignFactoryBarcode(buyer, orderId, request);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
    @DeleteMapping("/{buyer}/orders/{orderId}/barcode-assignment/cartons/{cartonId}")
    public CartonScanTransaction unassignFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId
    ) {
        return service.unassignFactoryBarcode(buyer, orderId, cartonId);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/{cartonId}/reset-weight")
    public CartonScanTransaction resetCartonWeight(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody CartonWeightResetRequest request
    ) {
        return service.resetCartonWeight(buyer, orderId, cartonId, request);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/factory-barcode/scan")
    public CartonScanTransaction scanAssignedFactoryBarcode(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody FactoryBarcodeScanRequest request
    ) {
        return service.scanAssignedFactoryBarcode(buyer, orderId, request);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/items/scan-lookup")
    public CartonItemLookupResponse lookupGeneratedItems(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonItemLookupRequest request
    ) {
        return service.lookupGeneratedItems(buyer, orderId, request);
    }

    /**
     * Zebra USB HID flow: match the QA code and atomically reserve the next PLANNED carton.
     * No Android/mobile camera page and no manual carton selection are required.
     */
    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/scan-next")
    public CartonScanTransaction scanNextFromZebra(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody ZebraScanRequest request
    ) {
        throw new IllegalArgumentException("Legacy QA scanning is retired. Scan the assigned Factory Barcode using factory-barcode/scan.");
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/{cartonId}/manual-complete")
    public CartonScanTransaction completePlannedCartonManually(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody CartonManualCompleteRequest request
    ) {
        throw new IllegalArgumentException("Use assigned Factory Barcode scanning and manual-weight, or the manual inspection workflow.");
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/cartons/{cartonId}/scan")
    public CartonScanTransaction scanPlannedCarton(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @PathVariable String cartonId,
            @RequestBody CartonPlanScanRequest request
    ) {
        throw new IllegalArgumentException("Scan the assigned Factory Barcode using factory-barcode/scan.");
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/lookup")
    public CartonLookupResponse lookup(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonLookupRequest request
    ) {
        return service.lookup(buyer, orderId, request);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/orders/{orderId}/transactions")
    public CartonScanTransaction start(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestBody CartonStartRequest request
    ) {
        throw new IllegalArgumentException("Scan the assigned Factory Barcode using factory-barcode/scan.");
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @GetMapping("/{buyer}/stations/{stationCode}/current")
    public CartonScanTransaction current(
            @PathVariable String buyer,
            @PathVariable String stationCode
    ) {
        return service.current(buyer, stationCode);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @GetMapping("/{buyer}/transactions/{transactionId}")
    public CartonScanTransaction get(
            @PathVariable String buyer,
            @PathVariable String transactionId
    ) {
        return service.get(buyer, transactionId);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @PostMapping("/{buyer}/transactions/{transactionId}/manual-weight")
    public CartonScanTransaction manualWeight(
            @PathVariable String buyer,
            @PathVariable String transactionId,
            @RequestBody ManualWeightRequest request
    ) {
        return service.manualWeight(buyer, transactionId, request);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @GetMapping("/{buyer}/orders/{orderId}/progress")
    public CartonProgressResponse progress(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.progress(buyer, orderId);
    }

    @PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    @GetMapping("/{buyer}/orders/{orderId}/recent")
    public List<CartonScanTransaction> recent(
            @PathVariable String buyer,
            @PathVariable String orderId
    ) {
        return service.recent(buyer, orderId);
    }

    /* PLC Gateway endpoints. The gateway authenticates with a normal service-user JWT. */
    @PreAuthorize("@accessControl.canWeightCheck()")
    @GetMapping("/plc/stations/{stationCode}/job")
    public PlcJobResponse currentPlcJob(@PathVariable String stationCode) {
        return service.currentPlcJob(stationCode);
    }

    @PreAuthorize("@accessControl.canWeightCheck()")
    @PostMapping("/plc/weights")
    public CartonScanTransaction receivePlcWeight(@RequestBody PlcWeightRequest request) {
        return service.receivePlcWeight(request);
    }
}
