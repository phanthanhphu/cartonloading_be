package org.bsl.cartonloading.buyers.lululemon.service;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.service.RfidPdaDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.RfidPdaDeviceFacade;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.stereotype.Service;
import org.bsl.cartonloading.buyers.lululemon.dto.RecoveryRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.RebindRequest;
import java.util.List;

/** RFID registry: ADMIN may assign one Reader to many Factories; locks are per Reader serial. */



@Service
public class RfidPdaManagementOperationsService {
    private final RfidPdaDeviceFacade service;
    private final LululemonDeviceStatusNotifier status;
    private final RfidPdaDeviceRepository registry;
    public RfidPdaManagementOperationsService(RfidPdaDeviceFacade service,
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


    public RfidPdaDeviceService.Device test(
            String buyer, String serial) {
        var device = service.forBuyer(buyer, impl -> impl.managementDevicesWithDiscovery()).stream()
                .filter(d -> serial.equals(d.serial())).findFirst().orElse(null);
        boolean online = device != null && device.online();
        status.readerObserved(serial, online ? AppConstants.DeviceConnection.CONNECTED : AppConstants.DeviceConnection.DISCONNECTED,
                online ? "ADB detected by Admin Test" : "PDA not detected / unauthorized by Admin Test");
        if (device == null) throw new IllegalArgumentException("RFID reader was not detected");
        return device;
    }


    public List<RfidPdaDeviceService.Device> list(String buyer) {

        return service.forBuyer(buyer, impl -> impl.managementDevices());
    }


    public RfidPdaDeviceService.Device configure(String buyer, String serial,
            RfidPdaDeviceService.GlobalDeviceSettings settings) {

        var priorFactories = readerFactories(serial);
        var saved = service.forBuyer(buyer, impl -> impl.configureDevice(serial, settings));
        var currentFactories = readerFactories(serial);
        for (String former : priorFactories) if (!currentFactories.contains(former))
            status.removed(former, "RFID", serial);
        status.readerConfigured(saved.serial());
        return saved;
    }


    public RfidPdaDeviceService.Device makeDefault(String buyer, String serial,
            String factoryCode) {

        var saved = service.forBuyer(buyer, impl -> impl.setDefault(factoryCode, serial));
        status.readerConfigured(saved.serial());
        return saved;
    }

public RfidPdaDeviceService.Device rebind(
            String buyer, String serial, RebindRequest body) {
        if (body == null) throw new IllegalArgumentException("Missing RFID replacement request.");
        var saved = service.forBuyer(buyer,
                impl -> impl.rebindFactoryReader(serial, body.newSerial(), body.factoryCode(), body.settings()));
        status.readerConfigured(saved.serial());
        return saved;
    }

public RfidPdaDeviceService.CaptureResult recover(String buyer, String serial,
            RecoveryRequest body) {

        return service.forBuyer(buyer, impl -> impl.forceRelease(serial, body == null ? null : body.reason(),
                body != null && body.inventoryStopped()));
    }


    public void deleteDevice(String buyer, String serial) {

        var affected = readerFactories(serial);
        service.forBuyer(buyer, impl -> { impl.deleteDevice(serial); return null; });
        for (String factory : affected) status.removed(factory, "RFID", serial);
    }


    public void unassign(String buyer, String serial,
            String factoryCode) {

        service.forBuyer(buyer, impl -> { impl.unassign(factoryCode, serial); return null; });
        status.removed(factoryCode, "RFID", serial);
    }
}
