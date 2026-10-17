package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.dto.RfidEpcRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.RfidStartRequest;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonRfidService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING + "/pos/{poId}/rfid")
public class LululemonRfidController {
    private final LululemonRfidService service;
    public LululemonRfidController(LululemonRfidService service) { this.service = service; }

    private void assertBuyer(String buyer) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer)) {
            throw new IllegalArgumentException("Lululemon RFID endpoints require LULULEMON buyer.");
        }
    }

    @GetMapping("/history")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public List<RfidScanSession> history(@PathVariable String buyer, @PathVariable String orderId,
                                         @PathVariable String poId, @RequestParam String sscc) {
        assertBuyer(buyer);
        return service.history(orderId, poId, sscc);
    }

    @GetMapping("/latest")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidScanSession latest(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestParam String sscc) {
        assertBuyer(buyer);
        return service.latest(orderId, poId, sscc);
    }

    @PostMapping("/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidScanSession start(@PathVariable String buyer, @PathVariable String orderId,
                                 @PathVariable String poId, @RequestBody RfidStartRequest request) {
        assertBuyer(buyer);
        return service.start(orderId, poId, request);
    }

    @PostMapping("/reads")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidScanSession reads(@PathVariable String buyer, @PathVariable String orderId,
                                 @PathVariable String poId, @RequestParam String sscc,
                                 @RequestBody RfidEpcRequest request) {
        assertBuyer(buyer);
        return service.ingest(orderId, poId, sscc, request);
    }

    @PostMapping("/finish")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidScanSession finish(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestBody RfidEpcRequest request,
                                  @RequestParam String sscc) {
        assertBuyer(buyer);
        return service.finish(orderId, poId, sscc, request == null ? null : request.sessionId());
    }

    @PostMapping("/reopen")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public RfidScanSession reopen(@PathVariable String buyer, @PathVariable String orderId,
                                  @PathVariable String poId, @RequestParam String sscc,
                                  @RequestParam String reason) {
        assertBuyer(buyer);
        return service.reopen(orderId, poId, sscc, reason);
    }
}
