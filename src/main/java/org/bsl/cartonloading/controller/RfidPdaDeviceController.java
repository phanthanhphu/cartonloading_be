package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.dto.CaptureRequest;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaOperationsFacade;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Existing Lululemon Spring Boot API; no additional web service or Android APK required. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING + "/pos/{poId}/rfid/devices")
public class RfidPdaDeviceController {
    private final RfidPdaOperationsFacade actions;
    public RfidPdaDeviceController(RfidPdaOperationsFacade actions) { this.actions = actions; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public List<RfidPdaDeviceService.Device> devices(@PathVariable String buyer, @RequestParam String stationCode, @RequestParam String factoryCode) {
        return actions.forBuyer(buyer, impl -> impl.devices(buyer, stationCode, factoryCode));
    }

    @PostMapping("/{serial}/start")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult start(@PathVariable String buyer, @PathVariable String orderId,
                                                     @PathVariable String poId, @PathVariable String serial,
                                                     @RequestBody CaptureRequest body) {
        return actions.forBuyer(buyer, impl -> impl.start(buyer, orderId, poId, serial, body));
    }

    @PostMapping("/{serial}/stop")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult stop(@PathVariable String buyer, @PathVariable String orderId,
                                                    @PathVariable String poId, @PathVariable String serial,
                                                    @RequestBody CaptureRequest body) {
        return actions.forBuyer(buyer, impl -> impl.stop(buyer, orderId, poId, serial, body));
    }

    @PostMapping("/{serial}/preview")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.Preview preview(@PathVariable String buyer, @PathVariable String orderId,
                                                 @PathVariable String poId, @PathVariable String serial,
                                                 @RequestBody CaptureRequest body) {
        return actions.forBuyer(buyer, impl -> impl.preview(buyer, orderId, poId, serial, body));
    }

    @PostMapping("/{serial}/release")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult release(@PathVariable String buyer, @PathVariable String orderId,
                                                       @PathVariable String poId, @PathVariable String serial,
                                                       @RequestBody CaptureRequest body) {
        return actions.forBuyer(buyer, impl -> impl.release(buyer, orderId, poId, serial, body));
    }

}
