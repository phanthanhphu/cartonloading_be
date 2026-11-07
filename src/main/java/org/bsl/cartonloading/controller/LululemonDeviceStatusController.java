package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.agent.ScaleAgentBroker;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import java.util.Map;
import java.util.stream.Collectors;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

/** Read-only, non-probing last-observed status for Device Management. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/device-status")
public class LululemonDeviceStatusController {
    private final LululemonDeviceStatusNotifier notifier;
    private final ScalePlcDeviceRepository scales;
    private final ScaleAgentBroker agents;
    public LululemonDeviceStatusController(LululemonDeviceStatusNotifier notifier,
            ScalePlcDeviceRepository scales, ScaleAgentBroker agents) {
        this.notifier = notifier; this.scales = scales; this.agents = agents;
    }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<LululemonDeviceStatusNotifier.DeviceStatus> snapshot(@PathVariable String buyer) {
        // Mask stale Connected observations when the Windows Agent stopped heartbeating.
        // Only modifies the returned status: no hardware probes, database writes or socket timers.
        Map<String, Boolean> online = scales.findAll().stream()
            .filter(d -> AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())
                && d.getFactoryCode() != null
                && ScalePlcDeviceService.DELTA_SERIAL.equalsIgnoreCase(d.getConnectionType())
                && ScalePlcDeviceService.LOCAL_AGENT.equals(ScalePlcDeviceService.serialLocation(d.getSerialConnectionLocation())))
            .collect(Collectors.toMap(d -> d.getFactoryCode().toUpperCase(java.util.Locale.ROOT),
                d -> agents.status(d.getFactoryCode()).online(), (a, b) -> a));
        return notifier.snapshot().stream().map(row -> {
            if (!"PLC".equals(row.kind()) || !Boolean.FALSE.equals(online.get(row.factoryCode())) ||
                !AppConstants.DeviceConnection.CONNECTED.equals(row.state())) return row;
            return new LululemonDeviceStatusNotifier.DeviceStatus(row.module(), row.action(), row.factoryCode(),
                row.kind(), row.deviceCode(), AppConstants.DeviceConnection.DISCONNECTED,
                "Factory Local Agent is offline; prior PLC observation expired", java.time.Instant.now());
        }).toList();
    }
}
