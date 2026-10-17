package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Existing Lululemon Spring Boot API; no additional web service or Android APK required. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING + "/pos/{poId}/rfid/devices")
public class RfidPdaDeviceController {
    private final RfidPdaDeviceService service;
    public RfidPdaDeviceController(RfidPdaDeviceService service) { this.service = service; }

    public record CaptureRequest(String stationCode, String sscc, String sessionId) { }

    private void buyer(String buyer) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer))
            throw new IllegalArgumentException("Only Lululemon supports RF88 ADB control.");
    }

    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public List<RfidPdaDeviceService.Device> devices(@PathVariable String buyer, @RequestParam String stationCode, @RequestParam String factoryCode) {
        buyer(buyer);
        return service.devices(stationCode, factoryCode);
    }

    @PostMapping("/{serial}/start")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidPdaDeviceService.CaptureResult start(@PathVariable String buyer, @PathVariable String orderId,
                                                     @PathVariable String poId, @PathVariable String serial,
                                                     @RequestBody CaptureRequest body) {
        buyer(buyer);
        return service.start(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId());
    }

    @PostMapping("/{serial}/stop")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidPdaDeviceService.CaptureResult stop(@PathVariable String buyer, @PathVariable String orderId,
                                                    @PathVariable String poId, @PathVariable String serial,
                                                    @RequestBody CaptureRequest body) {
        buyer(buyer);
        return service.stop(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId());
    }

    @PostMapping("/{serial}/preview")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidPdaDeviceService.Preview preview(@PathVariable String buyer, @PathVariable String orderId,
                                                 @PathVariable String poId, @PathVariable String serial,
                                                 @RequestBody CaptureRequest body) {
        buyer(buyer);
        return service.preview(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId());
    }

    @PostMapping("/{serial}/release")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public RfidPdaDeviceService.CaptureResult release(@PathVariable String buyer, @PathVariable String orderId,
                                                       @PathVariable String poId, @PathVariable String serial,
                                                       @RequestBody CaptureRequest body) {
        buyer(buyer);
        return service.release(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId());
    }
}
