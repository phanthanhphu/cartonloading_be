package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.model.User;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ManagementReportAssignedFactoryTest {
    private User adminAssignedF1() {
        User user = new User();
        user.setRole(AppConstants.Role.ADMIN);
        user.setEnabled(true);
        user.setFactoryPermissions(List.of("F1"));
        user.setBuyerAccessPermissions(Map.of(AppConstants.Buyer.LULULEMON,
                List.of(AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED)));
        return user;
    }

    private ManagementReportService.Filters filters(String factory) {
        return new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), factory, "", "", "");
    }

    @Test void adminWithOptionTwoCannotQueryAnotherFactory() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(adminAssignedF1());
        ManagementReportService service = new ManagementReportService(mongo, access);
        assertThrows(AccessDeniedException.class, () -> service.report(filters("F2"), 0, 25));
        assertThrows(AccessDeniedException.class, () -> service.export(filters("F2")));
        verifyNoInteractions(mongo);
    }

    @Test void adminWithOptionTwoOnlyReceivesAssignedPoWhenFilterIsAll() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(adminAssignedF1());
        PurchaseOrder f1 = new PurchaseOrder();
        f1.setId("po-f1"); f1.setBuyerCode("LULULEMON"); f1.setOrderId("order-1");
        f1.setFactoryCode("F1"); f1.setPoNumber("PO-1"); f1.setPlannedCartons(10);
        f1.setShippingDates(List.of(LocalDate.of(2026, 11, 12)));
        PurchaseOrder f2 = new PurchaseOrder();
        f2.setId("po-f2"); f2.setBuyerCode("LULULEMON"); f2.setOrderId("order-1");
        f2.setFactoryCode("F2"); f2.setPoNumber("PO-2"); f2.setPlannedCartons(20);
        f2.setShippingDates(List.of(LocalDate.of(2026, 11, 12)));
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class)))
                .thenReturn(new ArrayList<>(List.of(f1, f2)));
        OrderDocument order = new OrderDocument();
        order.setId("order-1"); order.setBuyerCode("LULULEMON"); order.setOrderName("Order AA");
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        when(mongo.find(any(Query.class), eq(org.bsl.cartonloading.model.Carton.class))).thenReturn(List.of());
        when(mongo.find(any(Query.class), eq(Document.class), eq("po_shipping_day_submissions"))).thenReturn(List.of());

        var report = new ManagementReportService(mongo, access).report(filters(""), 0, 25);
        assertEquals(1, report.pos().size());
        assertEquals("F1", report.pos().get(0).factoryCode());
        assertEquals(List.of("F1"), report.options().factories());
        assertEquals(1, report.totals().factories());
    }
}
