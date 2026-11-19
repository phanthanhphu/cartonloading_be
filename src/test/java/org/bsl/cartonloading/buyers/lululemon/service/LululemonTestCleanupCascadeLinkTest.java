package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The TEST ONLY UI must never use an independent, incomplete deletion inventory. */
class LululemonTestCleanupCascadeLinkTest {
    @Test void previewAndPurgeUseSameCascadeAsRegularOrderDelete() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        AuditLogService audit = mock(AuditLogService.class);
        Environment env = mock(Environment.class);
        var cascade = mock(LululemonOrderCascadeDeleteService.class);
        var cleanup = new LululemonTestCleanupService(mongo, audit, env, cascade);
        ReflectionTestUtils.setField(cleanup, "enabled", true);
        OrderDocument order = new OrderDocument();
        order.setId("order-A");
        order.setOrderName("Production-01");
        order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        when(mongo.findById("order-A", OrderDocument.class)).thenReturn(order);
        when(cascade.previewCounts("order-A"))
                .thenReturn(Map.of("Shipping day submissions (Report)", 3L));
        when(cascade.deleteAll("order-A", "Production-01", "DELETE Production-01"))
                .thenReturn(Map.of("Shipping day submissions (Report)", 3L, "Order", 1L));

        var preview = cleanup.preview(AppConstants.Buyer.LULULEMON, "order-A");
        assertEquals(3L, ((Map<?, ?>) preview.get("counts")).get("Shipping day submissions (Report)"));
        var result = cleanup.purge(AppConstants.Buyer.LULULEMON, "order-A", "DELETE Production-01");
        assertEquals(3L, ((Map<?, ?>) result.get("deleted")).get("Shipping day submissions (Report)"));
        verify(cascade).previewCounts("order-A");
        verify(cascade).deleteAll("order-A", "Production-01", "DELETE Production-01");
        verify(mongo, never()).remove(any(), anyString());
    }
}
