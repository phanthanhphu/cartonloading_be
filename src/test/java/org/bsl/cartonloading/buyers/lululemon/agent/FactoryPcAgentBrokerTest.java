package org.bsl.cartonloading.buyers.lululemon.agent;

import org.bsl.cartonloading.buyers.lululemon.repository.ScalePlcDeviceRepository;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Regression: two independent PCs in one Factory, one PC serves multiple COMs. */
class FactoryPcAgentBrokerTest {
    private final FactoryPcAgentRepository repo = mock(FactoryPcAgentRepository.class);
    private final ScalePlcDeviceRepository scales = mock(ScalePlcDeviceRepository.class);
    private final FactoryCatalogService factories = mock(FactoryCatalogService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final FactoryPcAgentBroker broker = new FactoryPcAgentBroker(repo, factories, scales, audit, mongo);
    private final Map<String, FactoryPcAgentRegistration> saved = new ConcurrentHashMap<>();

    private void setup() {
        when(repo.findByFactoryCodeOrderByDisplayNameAsc("F5")).thenAnswer(i -> saved.values().stream().filter(
            r -> r.getFactoryCode().equals("F5")).toList());
        when(repo.findById(anyString())).thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(0))));
        when(repo.save(any())).thenAnswer(i -> {
            FactoryPcAgentRegistration row = i.getArgument(0);
            saved.put(row.getId(), row);
            return row;
        });
        when(scales.findAll()).thenReturn(List.of());
    }
    @Test void independentPcsAndMultipleComPortsAreReadOnly() throws Exception {
        setup();
        var pc1 = broker.enroll("F5", "F5-Scale-PC-1");
        var pc2 = broker.enroll("F5", "F5-Scale-PC-2");
        assertNotEquals(pc1.agentId(), pc2.agentId());
        String instance1 = UUID.randomUUID().toString();
        String instance2 = UUID.randomUUID().toString();
        assertNull(broker.poll("F5", pc1.agentId(), pc1.token(), instance1));
        assertNull(broker.poll("F5", pc2.agentId(), pc2.token(), instance2));
        assertEquals(2, broker.list("F5").size());
        assertThrows(ResponseStatusException.class, () -> broker.poll("F3", pc1.agentId(), pc1.token(), instance1));
        var ports = CompletableFuture.supplyAsync(() -> {
            try { return broker.ports("F5", pc1.agentId()); } catch (Exception e) { throw new CompletionException(e); }
        });
        FactoryPcAgentBroker.Job job = awaitJob(pc1, instance1);
        assertEquals("LIST_PORTS", job.command());
        broker.result("F5", pc1.agentId(), pc1.token(), instance1,
            new FactoryPcAgentBroker.Result(job.jobId(), true, null, List.of("COM4", "COM8"), null));
        assertEquals(List.of("COM4", "COM8"), ports.get(3, TimeUnit.SECONDS));
        for (String port : List.of("COM4", "COM8")) {
            var read = CompletableFuture.supplyAsync(() -> {
                try { return broker.read("F5", pc1.agentId(), port, 9600, 7, "EVEN", 1, 1, 0, "UINT16"); }
                catch (Exception e) { throw new CompletionException(e); }
            });
            var request = awaitJob(pc1, instance1);
            assertEquals("READ_D", request.command());
            assertEquals(port, request.serialPort());
            broker.result("F5", pc1.agentId(), pc1.token(), instance1,
                new FactoryPcAgentBroker.Result(request.jobId(), true, 37L, null, null));
            assertEquals(37L, read.get(3, TimeUnit.SECONDS));
        }
        // Revoking PC1 does not disconnect PC2 or rotate PC2's key.
        broker.revoke("F5", pc1.agentId());
        assertThrows(ResponseStatusException.class, () -> broker.poll("F5", pc1.agentId(), pc1.token(), instance1));
        assertNull(broker.poll("F5", pc2.agentId(), pc2.token(), instance2));
    }
    private FactoryPcAgentBroker.Job awaitJob(FactoryPcAgentBroker.Enrollment pc, String instance) throws Exception {
        FactoryPcAgentBroker.Job job = null;
        for (int i = 0; i < 80 && job == null; i++) {
            Thread.sleep(20);
            job = broker.poll("F5", pc.agentId(), pc.token(), instance);
        }
        assertNotNull(job);
        return job;
    }
}
