package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** RFID registry: ADMIN may assign one Reader to many Factories; locks are per Reader serial. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/rfid-pda-management")
@PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
public class RfidPdaManagementController {
    private final RfidPdaDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    private final RfidPdaDeviceRepository registry;
    public RfidPdaManagementController(RfidPdaDeviceFacade service,
            LululemonDeviceStatusNotifier status,
            RfidPdaDeviceRepository registry) {
        this.service = service; this.status = status; this.registry = registry;
    }
    private java.util.Set<String> readerFactories(String serial) {
        return registry.findById(serial).map(reader -> {
            var factories = new java.util.LinkedHashSet<String>();
            if (reader.getFactoryCodes() != null) factories.addAll(reader.getFactoryCodes());
            if (reader.getFactoryCode() != null) factories.add(reader.getFactoryCode());
            return factories;
        }).orElseGet(java.util.LinkedHashSet::new);
    }

    /** Explicit Admin Test. ADB discovery happens ONLY when pressed, not on list rendering. */
    @PostMapping("/{serial}/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device test(
            @PathVariable String buyer, @PathVariable String serial) {
        var device = service.forBuyer(buyer, impl -> impl.managementDevices()).stream()
                .filter(d -> serial.equals(d.serial())).findFirst().orElse(null);
        boolean online = device != null && device.online();
        status.readerObserved(serial, online ? AppConstants.DeviceConnection.CONNECTED : AppConstants.DeviceConnection.DISCONNECTED,
                online ? "ADB detected by Admin Test" : "PDA not detected / unauthorized by Admin Test");
        if (device == null) throw new IllegalArgumentException("RFID reader was not detected");
        return device;
    }
    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<RfidPdaDeviceService.Device> list(@PathVariable String buyer) {

        return service.forBuyer(buyer, impl -> impl.managementDevices());
    }

    @PutMapping("/{serial}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device configure(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RfidPdaDeviceService.GlobalDeviceSettings settings) {

        var priorFactories = readerFactories(serial);
        var saved = service.forBuyer(buyer, impl -> impl.configureDevice(serial, settings));
        var currentFactories = readerFactories(serial);
        for (String former : priorFactories) if (!currentFactories.contains(former))
            status.removed(former, "RFID", serial);
        status.readerConfigured(saved.serial());
        return saved;
    }

    @PutMapping("/{serial}/default")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device makeDefault(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {

        var saved = service.forBuyer(buyer, impl -> impl.setDefault(factoryCode, serial));
        status.readerConfigured(saved.serial());
        return saved;
    }

    public record RebindRequest(String newSerial, String factoryCode,
            RfidPdaDeviceService.GlobalDeviceSettings settings) { }

    @PostMapping("/{serial}/rebind")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.Device rebind(
            @PathVariable String buyer, @PathVariable String serial, @RequestBody RebindRequest body) {
        if (body == null) throw new IllegalArgumentException("Missing RFID replacement request.");
        var saved = service.forBuyer(buyer,
                impl -> impl.rebindFactoryReader(serial, body.newSerial(), body.factoryCode(), body.settings()));
        status.readerConfigured(saved.serial());
        return saved;
    }

    public record RecoveryRequest(String reason, boolean inventoryStopped) { }

    @PostMapping("/{serial}/recover")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public RfidPdaDeviceService.CaptureResult recover(@PathVariable String buyer, @PathVariable String serial,
            @RequestBody RecoveryRequest body) {

        return service.forBuyer(buyer, impl -> impl.forceRelease(serial, body == null ? null : body.reason(),
                body != null && body.inventoryStopped()));
    }

    @DeleteMapping("/{serial}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void deleteDevice(@PathVariable String buyer, @PathVariable String serial) {

        var affected = readerFactories(serial);
        service.forBuyer(buyer, impl -> { impl.deleteDevice(serial); return null; });
        for (String factory : affected) status.removed(factory, "RFID", serial);
    }

    @DeleteMapping("/{serial}/assignment")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void unassign(@PathVariable String buyer, @PathVariable String serial,
            @RequestParam String factoryCode) {

        service.forBuyer(buyer, impl -> { impl.unassign(factoryCode, serial); return null; });
        status.removed(factoryCode, "RFID", serial);
    }
}
