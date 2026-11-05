package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.ConveyorPlcDevice;
import org.bsl.cartonloading.buyers.lululemon.repository.ConveyorPlcDeviceRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.DeviceCommandEventRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.DeviceRuntimeSnapshotRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.StationDeviceBindingRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.SelectedFactoryScope;
import org.bsl.cartonloading.service.AuditLogService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConveyorConfigurationRevisionTest {
    private final ConveyorPlcDeviceRepository devices = mock(ConveyorPlcDeviceRepository.class);
    private final FactoryCatalogService factories = mock(FactoryCatalogService.class);
    private final ConveyorPlcDeviceService subject = new ConveyorPlcDeviceService(devices,
            mock(UserRepository.class), factories, mock(AuditLogService.class),
            mock(SelectedFactoryScope.class), mock(DeviceCommandEventRepository.class),
            mock(DeviceRuntimeSnapshotRepository.class), mock(StationDeviceBindingRepository.class),
            mock(ScalePlcDeviceRepository.class));

    private ConveyorPlcDeviceService.Settings settings(Long revision) {
        return new ConveyorPlcDeviceService.Settings("CONVEYOR-F3-PACK-01", "F3", "PACK-01",
                "PLC F3", "10.232.132.57", 1502, 2, 0, 1, 2, 3, 4,
                true, List.of(), "SIMULATOR", "MODBUS_TCP", revision);
    }

    @Test void olderTabCannotRestorePreviouslySavedEndpoint() {
        ConveyorPlcDevice stored = new ConveyorPlcDevice();
        stored.setDeviceCode("CONVEYOR-F3-PACK-01"); stored.setFactoryCode("F3");
        stored.setRevision(3L); stored.setUnitId(2);
        when(devices.findById(stored.getDeviceCode())).thenReturn(Optional.of(stored));
        var conflict = assertThrows(ResponseStatusException.class,
                () -> subject.save(stored.getDeviceCode(), settings(2L)));
        assertEquals(409, conflict.getStatusCode().value());
        assertTrue(conflict.getReason().contains("NOT saved"));
        verify(devices, never()).saveIfCurrent(any(), anyLong());
    }

    @Test void oldFrontendWithoutRevisionCannotOverwriteSavedConfiguration() {
        ConveyorPlcDevice stored = new ConveyorPlcDevice();
        stored.setDeviceCode("CONVEYOR-F3-PACK-01"); stored.setFactoryCode("F3");
        stored.setRevision(1L);
        when(devices.findById(stored.getDeviceCode())).thenReturn(Optional.of(stored));
        assertThrows(ResponseStatusException.class,
                () -> subject.save(stored.getDeviceCode(), settings(null)));
        verify(devices, never()).saveIfCurrent(any(), anyLong());
    }
}
