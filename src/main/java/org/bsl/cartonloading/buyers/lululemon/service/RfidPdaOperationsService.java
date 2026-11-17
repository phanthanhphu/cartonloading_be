package org.bsl.cartonloading.buyers.lululemon.service;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.buyers.lululemon.dto.CaptureRequest;

import java.util.List;

/** Existing Lululemon Spring Boot API; no additional web service or Android APK required. */


@Service
public class RfidPdaOperationsService {
    private final RfidPdaDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    public RfidPdaOperationsService(RfidPdaDeviceFacade service,
            LululemonDeviceStatusNotifier status) {
        this.service = service; this.status = status;
    }
    private static boolean isTransportFailure(RuntimeException error) {
        String text = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return text.contains("adb") || text.contains("offline") || text.contains("device not found")
                || text.contains("device unauthorized") || text.contains("connection refused")
                || text.contains("failed to connect") || text.contains("no devices/emulators");
    }

public List<RfidPdaDeviceService.Device> devices(String buyer, String stationCode, String factoryCode) {

        return service.forBuyer(buyer, impl -> impl.devices(stationCode, factoryCode));
    }


    public RfidPdaDeviceService.CaptureResult start(String buyer, String orderId,
                                                     String poId, String serial,
                                                     CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.start(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA START command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }


    public RfidPdaDeviceService.CaptureResult stop(String buyer, String orderId,
                                                    String poId, String serial,
                                                    CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.stop(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA STOP command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }


    public RfidPdaDeviceService.Preview preview(String buyer, String orderId,
                                                 String poId, String serial,
                                                 CaptureRequest body) {

        try {
            var result = service.forBuyer(buyer, impl -> impl.preview(serial, body.stationCode(), orderId, poId, body.sscc(), body.sessionId()));
            status.readerObserved(serial, AppConstants.DeviceConnection.CONNECTED, "RFID PDA PREVIEW command succeeded (not RFID PASS)");
            return result;
        } catch (RuntimeException e) {
            if (isTransportFailure(e)) status.readerObserved(serial, AppConstants.DeviceConnection.DISCONNECTED, "RFID PDA communication failed");
            throw e;
        }
    }


    public RfidPdaDeviceService.CaptureResult release(String buyer, String orderId,
                                                       String poId, String serial,
                                                       CaptureRequest body) {

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
