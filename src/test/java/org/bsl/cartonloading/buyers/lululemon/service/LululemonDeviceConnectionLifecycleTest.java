package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ConveyorPlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LululemonDeviceConnectionLifecycleTest {
    private final ScalePlcDeviceRepository scales = mock(ScalePlcDeviceRepository.class);
    private final ConveyorPlcDeviceRepository conveyors = mock(ConveyorPlcDeviceRepository.class);
    private final ScalePlcDeviceService scaleService = mock(ScalePlcDeviceService.class);
    private final ConveyorPlcDeviceService conveyorService = mock(ConveyorPlcDeviceService.class);
    private final LululemonDeviceStatusNotifier notifications = mock(LululemonDeviceStatusNotifier.class);
    private final LululemonDeviceConnectionLifecycle subject =
            new LululemonDeviceConnectionLifecycle(scales, conveyors, scaleService, conveyorService, notifications);

    private ScalePlcDevice scale() {
        ScalePlcDevice d = new ScalePlcDevice();
        d.setBuyerKey("LULULEMON"); d.setStationCode("SCALE-F3"); d.setFactoryCode("F3");
        d.setEnabled(true); d.setConnectionType("MODBUS_TCP");
        d.setHost("10.232.132.57"); d.setPort(1502); d.setUnitId(1);
        d.setRegisterAddress(0); d.setStableRegisterAddress(1);
        d.setEncoding("UINT16"); d.setKgPerUnit(0.001);
        return d;
    }

    private ConveyorPlcDevice conveyor() {
        ConveyorPlcDevice d = new ConveyorPlcDevice();
        d.setBuyerKey("LULULEMON"); d.setDeviceCode("CONVEYOR-F3"); d.setFactoryCode("F3");
        d.setStationCode("PACK-01"); d.setEnabled(true);
        d.setMode("SIMULATOR"); d.setProtocol("MODBUS_TCP");
        d.setHost("10.232.132.57"); d.setPort(1502); d.setUnitId(2);
        return d;
    }

    @Test void startupChecksEachEnabledSavedDeviceOnceAndDoesNotWriteCoils() throws IOException {
        var scale = scale(); var conveyor = conveyor();
        when(scales.findAll()).thenReturn(List.of(scale));
        when(conveyors.findAll()).thenReturn(List.of(conveyor));
        when(scaleService.testConnection(any())).thenReturn(new ScalePlcDeviceService.Reading(
            scale.getStationCode(), "F3", "MODBUS_TCP", 390, new BigDecimal("0.390"),
            Instant.now(), "CONNECTED / READ ONLY", null, true, false));
        when(conveyorService.testConnection(any())).thenReturn(new ConveyorPlcDeviceService.Status(
            conveyor.getDeviceCode(), "F3", "PACK-01", Instant.now(),
            false, false, false, false, false, "MODBUSPAL_SIMULATOR", "CONNECTED / READ ONLY"));
        subject.checkOnceOnStartup();
        subject.checkOnceOnStartup();
        verify(scaleService, times(1)).testConnection(argThat(s -> s.unitId() == 1));
        verify(conveyorService, times(1)).testConnection(argThat(s -> s.unitId() == 2));
        verify(conveyorService, never()).command(anyString(), anyString());
        verify(notifications).publishFresh(eq("F3"), eq("PLC"), eq("SCALE-F3"), eq("CONNECTED"), anyString());
        verify(notifications).publishFresh(eq("F3"), eq("CONVEYOR"), eq("CONVEYOR-F3"), eq("STOPPED"), anyString());
    }

    @Test void deltaSerialSavedProfileIsConnectedWithoutAnyWeightOrStableReads() throws IOException {
        var serial = scale();
        serial.setConnectionType("DELTA_SERIAL");
        serial.setSerialComPort("COM8");
        serial.setSerialBaudRate(9600);
        serial.setSerialDataBits(7);
        serial.setSerialParity("EVEN");
        serial.setSerialStopBits(1);
        serial.setStableRegisterAddress(null);  // no weighing indicator installed
        when(scaleService.testPlcConnection(any())).thenReturn(new ScalePlcDeviceService.PlcConnectionCheck(
            serial.getStationCode(), "F3", "DELTA_SERIAL", "COM8", 1,
            true, "PLC CONNECTED / NO SCALE REQUIRED / READ ONLY", Instant.now()));
        subject.afterScaleSaved(serial);
        verify(scaleService, times(1)).testPlcConnection(argThat(cfg ->
            "COM8".equals(cfg.serialComPort()) && cfg.stableRegisterAddress() == null));
        verify(scaleService, never()).testConnection(any());
        verify(notifications).publishFresh(eq("F3"), eq("PLC"), eq("SCALE-F3"),
            eq("CONNECTED"), contains("scale / weight not tested"));
    }

    @Test void deltaSerialConnectionFailureDoesNotPretendPLCIsConnected() throws IOException {
        var serial = scale();
        serial.setConnectionType("DELTA_SERIAL");
        serial.setSerialComPort("COM8");
        when(scaleService.testPlcConnection(any())).thenThrow(new IOException("Modbus ASCII timeout/no reply"));
        subject.afterScaleSaved(serial);
        verify(notifications).publishFresh(eq("F3"), eq("PLC"), eq("SCALE-F3"),
                eq("DISCONNECTED"), contains("timeout/no reply"));
        verify(scaleService, never()).testConnection(any());
    }

    @Test void savingConveyorNeverChangesScaleAndFailureOnlyChangesObservedStatus() throws IOException {
        var device = conveyor();
        when(conveyorService.testConnection(any())).thenThrow(new SocketTimeoutException("Read timed out"));
        assertDoesNotThrow(() -> subject.afterConveyorSaved(device));
        verify(notifications).configured("F3", "CONVEYOR", "CONVEYOR-F3");
        verify(notifications).publishFresh(eq("F3"), eq("CONVEYOR"), eq("CONVEYOR-F3"),
                eq("DISCONNECTED"), contains("Read timed out"));
        verifyNoInteractions(scaleService);
        verify(conveyorService, never()).command(anyString(), anyString());
    }

    @Test void unsavedUnitIdEditsDoNotMatchSavedFactoryStatus() {
        var d = conveyor();
        when(conveyors.findById(d.getDeviceCode())).thenReturn(Optional.of(d));
        var current = LululemonDeviceConnectionLifecycle.conveyorSettings(d);
        assertTrue(subject.matchesSavedConveyor(current));
        var changed = new ConveyorPlcDeviceService.Settings(current.deviceCode(), current.factoryCode(),
                current.stationCode(), current.name(), current.host(), current.port(), 1,
                current.commandCoil(), current.motorFeedbackCoil(), current.rfidSensorCoil(),
                current.weightSensorCoil(), current.faultCoil(), current.enabled(), current.assignedUserIds(),
                current.mode(), current.protocol());
        assertFalse(subject.matchesSavedConveyor(changed));
    }

    @Test void disabledDevicesAreNotProbed() throws IOException {
        var device = conveyor(); device.setEnabled(false);
        subject.probeConveyor(device, "Startup check");
        verifyNoInteractions(conveyorService);
        verify(notifications).publishFresh("F3", "CONVEYOR", "CONVEYOR-F3", "DISABLED", "Startup check: device disabled");
    }
}
