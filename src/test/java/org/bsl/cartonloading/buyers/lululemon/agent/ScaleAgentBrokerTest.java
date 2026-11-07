package org.bsl.cartonloading.buyers.lululemon.agent;

import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScaleAgentBrokerTest {
    private final ScaleAgentRegistrationRepository registrations = mock(ScaleAgentRegistrationRepository.class);
    private final ScalePlcDeviceRepository devices = mock(ScalePlcDeviceRepository.class);
    private final FactoryCatalogService factories = mock(FactoryCatalogService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final ScaleAgentBroker subject = new ScaleAgentBroker(registrations, devices, factories, audit, mongo);

    private ScaleAgentBroker.Enrollment enroll() {
        AtomicReference<ScaleAgentRegistration> row = new AtomicReference<>();
        when(devices.findAll()).thenReturn(List.of());
        when(registrations.findById("LULULEMON:F5")).thenAnswer(call -> Optional.ofNullable(row.get()));
        when(registrations.save(any())).thenAnswer(call -> { row.set(call.getArgument(0)); return row.get(); });
        return subject.enroll("F5");
    }

    @Test void factoryScopedSecretSupportsOnlyReadJobAndRejectsWrongFactory() throws Exception {
        var credential = enroll();
        String instance = java.util.UUID.randomUUID().toString();
        assertNull(subject.poll("F5", credential.agentId(), credential.token(), instance));
        assertTrue(subject.status("F5").online());
        assertThrows(ResponseStatusException.class, () ->
            subject.poll("F3", credential.agentId(), credential.token(), instance));
        var read = CompletableFuture.supplyAsync(() -> {
            try { return subject.read("F5", "COM8", 9600, 7, "EVEN", 1, 1, 0, "UINT16"); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        ScaleAgentBroker.Job job = null;
        for (int n = 0; n < 60 && job == null; n++) {
            Thread.sleep(25);
            job = subject.poll("F5", credential.agentId(), credential.token(), instance);
        }
        assertNotNull(job);
        assertEquals("READ_D", job.command());
        assertEquals(0, job.dRegister());
        assertThrows(ResponseStatusException.class, () ->
            subject.result("F5", credential.agentId(), credential.token(), java.util.UUID.randomUUID().toString(),
                new ScaleAgentBroker.Result("none", true, 0L, null)));
        final var completedJob = job;
        subject.result("F5", credential.agentId(), credential.token(), instance,
            new ScaleAgentBroker.Result(completedJob.jobId(), true, 0L, null));
        assertEquals(0L, read.get(3, TimeUnit.SECONDS));
    }

    @Test void rotatedTokenRevokesOldCredentialsImmediately() {
        var original = enroll();
        var rotated = subject.enroll("F5");
        assertNotEquals(original.agentId(), rotated.agentId());
        assertThrows(ResponseStatusException.class, () ->
            subject.poll("F5", original.agentId(), original.token(), java.util.UUID.randomUUID().toString()));
    }
}
