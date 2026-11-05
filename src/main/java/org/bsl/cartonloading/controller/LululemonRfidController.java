package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.service.facade.LululemonRfidFacade;

import org.bsl.cartonloading.buyers.lululemon.dto.RfidEpcRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.RfidStartRequest;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING + "/pos/{poId}/rfid")
public class LululemonRfidController {
    private final LululemonRfidFacade service;
    public LululemonRfidController(LululemonRfidFacade service) { this.service = service; }

    @GetMapping("/history")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public List<RfidScanSession> history(@PathVariable String buyer, @PathVariable String orderId,
                                         @PathVariable String poId, @RequestParam String sscc) {

        return service.forBuyer(buyer, impl -> impl.history(orderId, poId, sscc));
    }

    @GetMapping("/latest")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_READ_DATA)
    public RfidScanSession latest(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestParam String sscc) {

        return service.forBuyer(buyer, impl -> impl.latest(orderId, poId, sscc));
    }

    @PostMapping("/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public RfidScanSession start(@PathVariable String buyer, @PathVariable String orderId,
                                 @PathVariable String poId, @RequestBody RfidStartRequest request) {

        return service.forBuyer(buyer, impl -> impl.start(orderId, poId, request));
    }

    @PostMapping("/reads")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public RfidScanSession reads(@PathVariable String buyer, @PathVariable String orderId,
                                 @PathVariable String poId, @RequestParam String sscc,
                                 @RequestBody RfidEpcRequest request) {

        return service.forBuyer(buyer, impl -> impl.ingest(orderId, poId, sscc, request));
    }

    @PostMapping("/finish")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public RfidScanSession finish(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestBody RfidEpcRequest request,
                                  @RequestParam String sscc) {

        return service.forBuyer(buyer, impl -> impl.finish(orderId, poId, sscc, request == null ? null : request.sessionId()));
    }

    @PostMapping("/reopen")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidScanSession reopen(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestParam String sscc,
                                  @RequestParam String reason) {

        return service.forBuyer(buyer, impl -> impl.reopen(orderId, poId, sscc, reason));
    }
}
