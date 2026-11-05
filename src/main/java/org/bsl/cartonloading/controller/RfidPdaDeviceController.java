package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Existing Lululemon Spring Boot API; no additional web service or Android APK required. */
@RestController
@RequestMapping(AppConstants.ApiPath.SSCC_WORKFLOW_WEIGHING + "/pos/{poId}/rfid/devices")
public class RfidPdaDeviceController {
    private final RfidPdaDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    public RfidPdaDeviceController(RfidPdaDeviceFacade service,
            LululemonDeviceStatusNotifier status) {
        this.service = service; this.status = status;
    }
    private static boolean isTransportFailure(RuntimeException error) {
        String text = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return text.contains("adb") || text.contains("offline") || text.contains("device not found")
                || text.contains("device unauthorized") || text.contains("connection refused")
                || text.contains("failed to connect") || text.contains("no devices/emulators");
    }

    public record CaptureRequest(String stationCode, String sscc, String sessionId) { }
    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public List<RfidPdaDeviceService.Device> devices(@PathVariable String buyer, @RequestParam String stationCode, @RequestParam String factoryCode) {

        return service.forBuyer(buyer, impl -> impl.devices(stationCode, factoryCode));
    }

    @PostMapping("/{serial}/start")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult start(@PathVariable String buyer, @PathVariable String orderId,
                                                     @PathVariable String poId, @PathVariable String serial,
                                                     @RequestBody CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.start(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA START command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }

    @PostMapping("/{serial}/stop")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult stop(@PathVariable String buyer, @PathVariable String orderId,
                                                    @PathVariable String poId, @PathVariable String serial,
                                                    @RequestBody CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.stop(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA STOP command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }

    @PostMapping("/{serial}/preview")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.Preview preview(@PathVariable String buyer, @PathVariable String orderId,
                                                 @PathVariable String poId, @PathVariable String serial,
                                                 @RequestBody CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.preview(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA PREVIEW command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }

    @PostMapping("/{serial}/release")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public RfidPdaDeviceService.CaptureResult release(@PathVariable String buyer, @PathVariable String orderId,
                                                       @PathVariable String poId, @PathVariable String serial,
                                                       @RequestBody CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.release(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA RELEASE command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }
}
