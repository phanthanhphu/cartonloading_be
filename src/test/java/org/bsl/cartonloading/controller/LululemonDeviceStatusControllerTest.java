package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.agent.FactoryPcAgentBroker;
import org.bsl.cartonloading.buyers.lululemon.agent.ScaleAgentBroker;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** Regression: reload must consult the saved v2 PC Agent, not the old v1 Factory Agent. */
class LululemonDeviceStatusControllerTest {
    private final LululemonDeviceStatusNotifier notifier = mock(LululemonDeviceStatusNotifier.class);
    private final ScalePlcDeviceRepository scales = mock(ScalePlcDeviceRepository.class);
    private final ScaleAgentBroker v1Agents = mock(ScaleAgentBroker.class);
    private final FactoryPcAgentBroker pcAgents = mock(FactoryPcAgentBroker.class);
    private final LululemonDeviceStatusController controller =
        new LululemonDeviceStatusController(notifier, scales, v1Agents, pcAgents);

    private ScalePlcDevice scale(String id) {
        var d = new ScalePlcDevice();
        d.setStationCode("SCALE-F3");
        d.setBuyerKey(AppConstants.Buyer.LULULEMON);
        d.setFactoryCode("F3");
        d.setConnectionType("DELTA_SERIAL");
        d.setSerialConnectionLocation("LOCAL_AGENT");
        d.setSerialAgentId(id);
        return d;
    }

    private void observation(String kind, String state) {
        when(notifier.snapshot()).thenReturn(List.of(new LululemonDeviceStatusNotifier.DeviceStatus(
            "DEVICE", "STATUS", "F3", kind, "SCALE-F3", state, "Last PLC test", Instant.now())));
    }

    @Test void v2OnlineKeepsConnectedEvenWhenLegacyAgentIsOffline() {
        when(scales.findAll()).thenReturn(List.of(scale("PC-F3-a1b2c3d4e5f6")));
        when(pcAgents.list("F3")).thenReturn(List.of(new FactoryPcAgentBroker.PcStatus(
            "F3", "PC-F3-a1b2c3d4e5f6", "F3-PC", true, Instant.now())));
        observation("PLC", "CONNECTED");
        assertEquals("CONNECTED", controller.snapshot("LULULEMON").get(0).state());
        verifyNoInteractions(v1Agents);
    }

    @Test void v2OfflineMasksOnlyLastConnectedPlcObservation() {
        when(scales.findAll()).thenReturn(List.of(scale("PC-F3-a1b2c3d4e5f6")));
        when(pcAgents.list("F3")).thenReturn(List.of(new FactoryPcAgentBroker.PcStatus(
            "F3", "PC-F3-a1b2c3d4e5f6", "F3-PC", false, Instant.now())));
        observation("PLC", "CONNECTED");
        assertEquals("DISCONNECTED", controller.snapshot("LULULEMON").get(0).state());
        observation("PLC", "NOT_CHECKED");
        assertEquals("NOT_CHECKED", controller.snapshot("LULULEMON").get(0).state());
        observation("RFID", "CONNECTED");
        assertEquals("CONNECTED", controller.snapshot("LULULEMON").get(0).state());
    }

    @Test void legacyNoPcIdStillUsesV1AgentStatus() {
        when(scales.findAll()).thenReturn(List.of(scale(null)));
        when(v1Agents.status("F3")).thenReturn(new ScaleAgentBroker.AgentStatus(
            "F3", "SCALE-F3", true, true, Instant.now()));
        observation("PLC", "CONNECTED");
        assertEquals("CONNECTED", controller.snapshot("LULULEMON").get(0).state());
        verifyNoInteractions(pcAgents);
    }
}
