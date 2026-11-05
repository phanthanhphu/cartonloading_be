package org.bsl.cartonloading.common.socket;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.model.RfidPdaDevice;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ConveyorPlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidPdaDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pushes status OBSERVED during existing Test/Scan/Weight/Conveyor operations.
 * Does not probe devices, schedule polling, or infer physical connectivity from configuration.
 * Snapshots are in-memory and best-effort. A single read-only startup check can populate
 * them after restart; subsequent updates come only from Test, Save or business operations.
 */
@Component
public class LululemonDeviceStatusNotifier {
    public record DeviceStatus(String module, String action, String factoryCode, String kind,
                               String deviceCode, String state, String detail, Instant observedAt) { }

    private final SimpMessagingTemplate sockets;
    private final ScalePlcDeviceRepository scales;
    private final ConveyorPlcDeviceRepository conveyors;
    private final RfidPdaDeviceRepository readers;
    private final Map<String, DeviceStatus> observed = new ConcurrentHashMap<>();

    public LululemonDeviceStatusNotifier(SimpMessagingTemplate sockets,
            ScalePlcDeviceRepository scales, ConveyorPlcDeviceRepository conveyors,
            RfidPdaDeviceRepository readers) {
        this.sockets = sockets;
        this.scales = scales;
        this.conveyors = conveyors;
        this.readers = readers;
    }

    private static String key(String factory, String kind) { return factory + "|" + kind; }

    private boolean isRegistered(String factory, String kind, String deviceCode) {
        if ("PLC".equals(kind)) return scales.findById(deviceCode)
                .map(d -> factory.equalsIgnoreCase(d.getFactoryCode()) && d.isEnabled()).orElse(false);
        if ("CONVEYOR".equals(kind)) return conveyors.findById(deviceCode)
                .map(d -> factory.equalsIgnoreCase(d.getFactoryCode()) && d.isEnabled()).orElse(false);
        if ("RFID".equals(kind)) return readers.findById(deviceCode).map(d ->
                (d.getFactoryCodes() != null && d.getFactoryCodes().contains(factory))
                        || factory.equalsIgnoreCase(d.getFactoryCode())).orElse(false);
        return false;
    }

    private boolean isConfigured(String factory, String kind, String deviceCode) {
        if ("PLC".equals(kind)) return scales.findById(deviceCode)
                .map(d -> factory.equalsIgnoreCase(d.getFactoryCode())).orElse(false);
        if ("CONVEYOR".equals(kind)) return conveyors.findById(deviceCode)
                .map(d -> factory.equalsIgnoreCase(d.getFactoryCode())).orElse(false);
        return false;
    }

    public void publish(String factory, String kind, String deviceCode, String state, String detail) {
        publishInternal(factory, kind, deviceCode, state, detail, false);
    }

    /** Explicit Test / successful Save / one-time startup read. Always timestamp and push
     * the fresh observation even if the connection state did not change within 30 seconds.
     * This method does NOT read or poll the hardware.
     */
    public void publishFresh(String factory, String kind, String deviceCode, String state, String detail) {
        publishInternal(factory, kind, deviceCode, state, detail, true);
    }

    private void publishInternal(String factory, String kind, String deviceCode, String state,
                                 String detail, boolean fresh) {
        if (factory == null || factory.isBlank() || deviceCode == null || deviceCode.isBlank()) return;
        String code = factory.trim().toUpperCase(Locale.ROOT);
        String index = key(code, kind);
        DeviceStatus previous = observed.get(index);
        // No broadcast on every background scale sample. A real transition or a fresh
        // explicit observation after 30 seconds is enough; no extra hardware I/O.
        if (!fresh && previous != null && previous.deviceCode().equals(deviceCode) && previous.state().equals(state)
                && previous.observedAt().isAfter(Instant.now().minusSeconds(30))) return;
        // A Test against unsaved settings must not alter the SAVED Factory row.
        if (!"NOT_CONFIGURED".equals(state) && !"NOT_CHECKED".equals(state)
                && !("DISABLED".equals(state) ? isConfigured(code, kind, deviceCode)
                        : isRegistered(code, kind, deviceCode))) return;
        DeviceStatus event = new DeviceStatus("DEVICE", "STATUS", code, kind, deviceCode, state,
                detail == null ? "" : detail, Instant.now());
        observed.put(index, event);
        // ADMIN_TOPIC is authenticated and Admin-only; never send device state to all users.
        sockets.convertAndSend(AppConstants.OrganizationRealtime.ADMIN_TOPIC, event);
    }

    public void configured(String factory, String kind, String deviceCode) {
        if (factory == null || factory.isBlank()) return;
        observed.remove(key(factory.trim().toUpperCase(Locale.ROOT), kind));
        String state = "NOT_CHECKED";
        if ("PLC".equals(kind) && scales.findById(deviceCode)
                .map(d -> AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(d.getConnectionType())).orElse(false)) state = AppConstants.DeviceConnection.SIMULATOR;
        publish(factory, kind, deviceCode, state, "Configuration saved; connection not tested");
    }

    public void removed(String factory, String kind, String deviceCode) {
        if (factory == null || factory.isBlank()) return;
        observed.remove(key(factory.trim().toUpperCase(Locale.ROOT), kind));
        publish(factory, kind, deviceCode, "NOT_CONFIGURED", "Device configuration removed");
    }

    public void readerObserved(String serial, String state, String detail) {
        if (serial == null || serial.isBlank()) return;
        readers.findById(serial).ifPresent(r -> {
            Set<String> assigned = new LinkedHashSet<>();
            if (r.getFactoryCodes() != null) assigned.addAll(r.getFactoryCodes());
            if (r.getFactoryCode() != null) assigned.add(r.getFactoryCode());
            assigned.forEach(factory -> publish(factory, "RFID", serial, state, detail));
        });
    }

    public void readerConfigured(String serial) {
        readers.findById(serial).ifPresent(r -> {
            Set<String> assigned = new LinkedHashSet<>();
            if (r.getFactoryCodes() != null) assigned.addAll(r.getFactoryCodes());
            if (r.getFactoryCode() != null) assigned.add(r.getFactoryCode());
            assigned.forEach(factory -> configured(factory, "RFID", serial));
        });
    }

    /** Zero network/device probes. Configuration loaded from MongoDB; transient observations from this JVM. */
    public List<DeviceStatus> snapshot() {
        Map<String, DeviceStatus> rows = new TreeMap<>();
        for (ScalePlcDevice device : scales.findAll()) {
            if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(device.getBuyerKey()) || device.getFactoryCode() == null) continue;
            String factory = device.getFactoryCode().toUpperCase(Locale.ROOT);
            String state = !device.isEnabled() ? "DISABLED"
                    : AppConstants.DeviceConnection.SIMULATOR.equalsIgnoreCase(device.getConnectionType()) ? AppConstants.DeviceConnection.SIMULATOR : "NOT_CHECKED";
            add(rows, factory, "PLC", device.getStationCode(), state, "Saved PLC configuration");
        }
        for (RfidPdaDevice device : readers.findAll()) {
            if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(device.getBuyerKey())) continue;
            Set<String> factories = new LinkedHashSet<>();
            if (device.getFactoryCodes() != null) factories.addAll(device.getFactoryCodes());
            if (device.getFactoryCode() != null) factories.add(device.getFactoryCode());
            for (String raw : factories) {
                if (raw != null && !raw.isBlank()) add(rows, raw.toUpperCase(Locale.ROOT), "RFID",
                        device.getSerial(), "NOT_CHECKED", "Saved RFID reader; ADB not probed");
            }
        }
        for (ConveyorPlcDevice device : conveyors.findAll()) {
            if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(device.getBuyerKey()) || device.getFactoryCode() == null) continue;
            String state = !device.isEnabled() ? "DISABLED" : "NOT_CHECKED";
            add(rows, device.getFactoryCode().toUpperCase(Locale.ROOT), "CONVEYOR", device.getDeviceCode(), state,
                    "Saved Conveyor configuration; no automatic PLC probe");
        }
        return List.copyOf(rows.values());
    }

    private void add(Map<String, DeviceStatus> rows, String factory, String kind, String deviceCode,
                     String defaultState, String defaultDetail) {
        if (deviceCode == null) return;
        String index = key(factory, kind);
        DeviceStatus before = rows.get(index);
        if (before != null) {
            rows.put(index, new DeviceStatus("DEVICE", "STATUS", factory, kind, deviceCode,
                    "DUPLICATE", "Multiple configured devices for this Factory", Instant.EPOCH));
            return;
        }
        DeviceStatus current = observed.get(index);
        // Observation from another device, or prior removed configuration, is stale.
        if ("DISABLED".equals(defaultState) || AppConstants.DeviceConnection.SIMULATOR.equals(defaultState)) current = null;
        if (current == null || !deviceCode.equals(current.deviceCode()) || "NOT_CONFIGURED".equals(current.state())) {
            current = new DeviceStatus("DEVICE", "STATUS", factory, kind, deviceCode, defaultState, defaultDetail, Instant.EPOCH);
        }
        rows.put(index, current);
    }
}
