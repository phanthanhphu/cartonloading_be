package org.bsl.cartonloading.buyers.lululemon.service;

import com.mongodb.client.result.DeleteResult;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Protect the Order -> POs -> Shipping Day/Report deletion contract. */
class LululemonOrderCascadeDeleteServiceTest {
    private static final String REPORT_SUBMISSIONS = AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS;

    @AfterEach void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test void previewIncludesReportSubmissionsAndIsAlwaysBuyerAndOrderScoped() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        var service = new LululemonOrderCascadeDeleteService(mongo,
                mock(PoIdentityRegistryService.class), mock(AuditLogService.class));
        when(mongo.collectionExists(REPORT_SUBMISSIONS)).thenReturn(true);
        when(mongo.count(any(Query.class), eq(REPORT_SUBMISSIONS))).thenReturn(2L);

        var counts = service.previewCounts("order-A");

        assertEquals(2L, counts.get("Shipping day submissions (Report)"));
        assertTrue(counts.containsKey("Purchase Orders"));
        assertTrue(counts.containsKey("PO identity registry"));
        ArgumentCaptor<Query> filter = ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(filter.capture(), eq(REPORT_SUBMISSIONS));
        String query = filter.getValue().getQueryObject().toJson();
        assertTrue(query.contains("buyerCode") && query.contains("LULULEMON"), query);
        assertTrue(query.contains("orderId") && query.contains("order-A"), query);
        assertFalse(query.contains("order-B"), query);
    }

    @Test void deletionRemovesReportSubmissionBeforeParentAndKeepsAuditHistory() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        MongoTemplate mongo = mock(MongoTemplate.class);
        PoIdentityRegistryService registry = mock(PoIdentityRegistryService.class);
        AuditLogService audit = mock(AuditLogService.class);
        OrderDocument order = new OrderDocument();
        order.setId("order-A");
        order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        order.setOrderName("Production-01");
        when(mongo.findOne(any(Query.class), eq(OrderDocument.class))).thenReturn(order);
        when(mongo.collectionExists(REPORT_SUBMISSIONS)).thenReturn(true);
        when(mongo.remove(any(Query.class), eq(REPORT_SUBMISSIONS))).thenReturn(DeleteResult.acknowledged(2));
        when(mongo.remove(any(Query.class), eq(OrderDocument.class))).thenReturn(DeleteResult.acknowledged(1));
        var service = new LululemonOrderCascadeDeleteService(mongo, registry, audit);

        var deleted = service.deleteAll("order-A", "Production-01", "DELETE Production-01");

        assertEquals(2L, deleted.get("Shipping day submissions (Report)"));
        assertEquals(1L, deleted.get("Order"));
        ArgumentCaptor<Query> filter = ArgumentCaptor.forClass(Query.class);
        verify(mongo).remove(filter.capture(), eq(REPORT_SUBMISSIONS));
        String query = filter.getValue().getQueryObject().toJson();
        assertTrue(query.contains("order-A") && query.contains("LULULEMON"), query);
        var removalOrder = inOrder(mongo);
        removalOrder.verify(mongo).remove(any(Query.class), eq(REPORT_SUBMISSIONS));
        removalOrder.verify(mongo).remove(any(Query.class), eq(OrderDocument.class));
        verify(mongo, never()).remove(any(Query.class), eq(AppConstants.Collection.AUDIT_LOGS));
        verify(registry, times(3)).assertNoReservedForOrder("order-A");
        verify(registry).releaseForDeletedOrder("order-A");
    }
}
