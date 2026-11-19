package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bson.Document;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ManagementReportImportedScopeTest {
    @Test void assignedFactoryGrantCannotSeeUnscheduledPoFromAnotherFactory() {
        var mongo = mock(MongoTemplate.class);
        var access = mock(BuyerFactoryAccessService.class);
        var user = mock(User.class);
        when(access.currentUser()).thenReturn(user);
        when(user.isEnabled()).thenReturn(true);
        when(user.canLululemonViewManagementReports()).thenReturn(true);
        when(user.canLululemonReportAssignedFactories()).thenReturn(true);
        when(user.canLululemonReportAllFactories()).thenReturn(false);
        when(user.getFactoryPermissions()).thenReturn(List.of("F1"));
        PurchaseOrder a = new PurchaseOrder();
        a.setId("p1"); a.setBuyerCode(AppConstants.Buyer.LULULEMON);
        a.setOrderId("o1"); a.setFactoryCode("F1"); a.setPoNumber("PO1"); a.setPlannedCartons(4);
        PurchaseOrder b = new PurchaseOrder();
        b.setId("p2"); b.setBuyerCode(AppConstants.Buyer.LULULEMON);
        b.setOrderId("o1"); b.setFactoryCode("F2"); b.setPoNumber("PO2"); b.setPlannedCartons(7);
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class))).thenReturn(List.of(a, b));
        OrderDocument order = new OrderDocument();
        order.setId("o1"); order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class),
                eq(AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS))).thenReturn(List.of());
        var service = new ManagementReportService(mongo, access);
        var filter = new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), "", "", "", "", "ALL");
        var report = service.report(filter, 0, 25);
        assertEquals(1, report.totalRows());
        assertEquals("p1", report.pos().get(0).poId());
        assertEquals(4, report.totals().plannedCartons());
        assertEquals(List.of("F1"), service.dashboardOptions(filter).factories());
        var illegal = new ManagementReportService.Filters(filter.from(), filter.to(),
                "F2", "", "", "", "ALL");
        assertThrows(AccessDeniedException.class, () -> service.report(illegal, 0, 25));
    }
}
