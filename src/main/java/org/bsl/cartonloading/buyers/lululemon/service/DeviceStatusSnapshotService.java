package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.lululemon.agent.ScaleAgentBroker;
import org.bsl.cartonloading.buyers.lululemon.agent.FactoryPcAgentBroker;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import java.util.Map;
import java.util.stream.Collectors;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;





import java.util.List;
import org.springframework.stereotype.Service;

/** Read-only, non-probing last-observed status for Device Management. */
@Service
public class DeviceStatusSnapshotService {
    private final LululemonDeviceStatusNotifier notifier;
    private final ScalePlcDeviceRepository scales;
    private final ScaleAgentBroker agents;
    private final FactoryPcAgentBroker pcAgents;
    public DeviceStatusSnapshotService(LululemonDeviceStatusNotifier notifier,
            ScalePlcDeviceRepository scales, ScaleAgentBroker agents, FactoryPcAgentBroker pcAgents) {
        this.notifier = notifier; this.scales = scales; this.agents = agents; this.pcAgents = pcAgents;
    }

    /** Match the transport selected by the SAVED Scale profile; PC Agent v2 is not the v1 broker. */
    private boolean localAgentOnline(org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice scale) {
        String id = scale.getSerialAgentId();
        if (id != null && !id.isBlank()) {
            // A configured v2 PC must be online by its own ID; never fall back to v1 Agent status.
            return pcAgents.list(scale.getFactoryCode()).stream()
                .anyMatch(pc -> id.equals(pc.agentId()) && pc.online());
        }
        return agents.status(scale.getFactoryCode()).online(); // Legacy v1 configuration.
    }

    public List<LululemonDeviceStatusNotifier.DeviceStatus> snapshot() {
        // Mask stale Connected observations when the Windows Agent stopped heartbeating.
        // Only modifies the returned status: no hardware probes, database writes or socket timers.
        Map<String, Boolean> online = scales.findAll().stream()
            .filter(d -> AppConstants.Buyer.LULULEMON.equalsIgnoreCase(d.getBuyerKey())
                && d.getFactoryCode() != null
                && ScalePlcDeviceService.DELTA_SERIAL.equalsIgnoreCase(d.getConnectionType())
                && ScalePlcDeviceService.LOCAL_AGENT.equals(ScalePlcDeviceService.serialLocation(d.getSerialConnectionLocation())))
            .collect(Collectors.toMap(d -> d.getFactoryCode().toUpperCase(java.util.Locale.ROOT),
                this::localAgentOnline, (a, b) -> a));
        return notifier.snapshot().stream().map(row -> {
            if (!"PLC".equals(row.kind()) || !Boolean.FALSE.equals(online.get(row.factoryCode())) ||
                !AppConstants.DeviceConnection.CONNECTED.equals(row.state())) return row;
            return new LululemonDeviceStatusNotifier.DeviceStatus(row.module(), row.action(), row.factoryCode(),
                row.kind(), row.deviceCode(), AppConstants.DeviceConnection.DISCONNECTED,
                "Factory Local Agent is offline; prior PLC observation expired", java.time.Instant.now());
        }).toList();
    }
}
