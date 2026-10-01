package org.bsl.cartonloading.buyers.es.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.bsl.cartonloading.buyers.es.constants.WorkflowStage;

import org.bsl.cartonloading.common.constants.FilterValue;
import org.bsl.cartonloading.buyers.es.dto.barcode.BarcodeAssignmentPageResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.PackingMasterAssignmentPageResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeAssignRequest;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.service.CartonLoadingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Stage 2: explicit quantity confirmation and barcode assignment only. */
@Tag(name = WorkflowStage.PACKING_ASSIGN)
@RestController
@RequestMapping("/api/buyers/{buyer}/packing/orders/{orderId}/barcode-assignment")
@PreAuthorize("@esBuyerGuard.matches(#buyer) and @accessControl.canAccessBuyer(#buyer) and @accessControl.canAssignBarcode()")
public class PackingAssignmentController {
    private final CartonLoadingService service;
    public PackingAssignmentController(CartonLoadingService service) { this.service = service; }
    @GetMapping("/masters")
    public PackingMasterAssignmentPageResponse masters(
            @PathVariable String buyer,
            @PathVariable String orderId,
            @RequestParam(required = false) String poNumber,
            @RequestParam(required = false) String articleNumber,
            @RequestParam(required = false) String styleNumber,
            @RequestParam(required = false) String style,
            @RequestParam(defaultValue = "ALL") String assignmentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.listMasterRowsForBarcodeAssignment(
                buyer, orderId, poNumber, articleNumber, styleNumber, style, assignmentStatus, page, size
        );
    }

    @GetMapping("/cartons")
    public BarcodeAssignmentPageResponse cartons(@PathVariable String buyer, @PathVariable String orderId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = FilterValue.UNASSIGNED) String assignment,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return service.listCartonsForBarcodeAssignment(buyer, orderId, keyword, assignment, page, size);
    }
    @GetMapping("/check/{barcode}")
    public FactoryBarcode check(@PathVariable String buyer, @PathVariable String orderId, @PathVariable String barcode) {
        return service.checkFactoryBarcodeForAssignment(buyer, orderId, barcode);
    }
    @PostMapping
    public CartonScanTransaction assign(@PathVariable String buyer, @PathVariable String orderId,
            @RequestBody FactoryBarcodeAssignRequest request) {
        return service.assignFactoryBarcode(buyer, orderId, request);
    }
    @DeleteMapping("/cartons/{cartonId}")
    public CartonScanTransaction unassign(@PathVariable String buyer, @PathVariable String orderId, @PathVariable String cartonId) {
        return service.unassignFactoryBarcode(buyer, orderId, cartonId);
    }
}
