package org.bsl.cartonloading.buyers.es.controller;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeGenerateRequest;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeGenerateResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodePrintRequest;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeSequenceResponse;
import org.bsl.cartonloading.buyers.es.dto.barcode.FactoryBarcodeVoidRequest;
import org.bsl.cartonloading.buyers.es.model.FactoryBarcode;
import org.bsl.cartonloading.buyers.es.service.FactoryBarcodeService;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.BARCODE_WORKFLOW_FACTORY_BARCODES)
@PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_ACCESS)
public class FactoryBarcodeController {
    private final FactoryBarcodeService service;

    public FactoryBarcodeController(FactoryBarcodeService service) {
        this.service = service;
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @PostMapping("/generate")
    public FactoryBarcodeGenerateResponse generate(@RequestBody FactoryBarcodeGenerateRequest request) {
        return service.generate(request);
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @GetMapping
    public Page<FactoryBarcode> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String factoryCode,
            @RequestParam(required = false) Integer year,
            @RequestParam(defaultValue = AppConstants.RequestDefault.PAGE) int page,
            @RequestParam(defaultValue = AppConstants.RequestDefault.LARGE_PAGE_SIZE) int size
    ) {
        return service.list(keyword, status, factoryCode, year, page, size);
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @GetMapping("/sequence")
    public FactoryBarcodeSequenceResponse sequence(
            @RequestParam Integer year,
            @RequestParam String factoryCode
    ) {
        return service.sequence(year, factoryCode);
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @GetMapping("/{barcode}")
    public FactoryBarcode get(@PathVariable String barcode) {
        return service.get(barcode);
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @PostMapping("/mark-printed")
    public List<FactoryBarcode> markPrinted(@RequestBody FactoryBarcodePrintRequest request) {
        return service.markPrinted(request == null ? null : request.barcodes());
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_MANAGE_BARCODES)
    @PostMapping("/{barcode}/void")
    public FactoryBarcode voidBarcode(
            @PathVariable String barcode,
            @RequestBody(required = false) FactoryBarcodeVoidRequest request
    ) {
        return service.voidBarcode(barcode, request == null ? null : request.reason());
    }
}
