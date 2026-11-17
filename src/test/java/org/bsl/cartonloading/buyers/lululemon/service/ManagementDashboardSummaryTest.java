package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
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

class ManagementDashboardSummaryTest {
    private static ManagementReportService.Filters range(String factory) {
        return new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), factory, "", "", "");
    }

    private static User actor(String option, List<String> factories) {
        User user = new User();
        user.setRole(AppConstants.Role.ADMIN); // ADMIN cannot bypass the chosen report option.
        user.setEnabled(true);
        user.setFactoryPermissions(factories);
        user.setBuyerAccessPermissions(Map.of(AppConstants.Buyer.LULULEMON, List.of(option)));
        return user;
    }

    private static PurchaseOrder po(String id, String factory, int planned, LocalDate date) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id); po.setBuyerCode(AppConstants.Buyer.LULULEMON);
        po.setOrderId("order1"); po.setFactoryCode(factory);
        po.setPoNumber(id); po.setPlannedCartons(planned);
        po.setShippingDates(List.of(date));
        return po;
    }

    private static void fixtures(MongoTemplate mongo) {
        var day = LocalDate.of(2026, 11, 12);
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class))).thenReturn(new ArrayList<>(List.of(
                po("p1", "F1", 10, day), po("p2", "F2", 20, day))));
        OrderDocument order = new OrderDocument();
        order.setId("order1"); order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        order.setOrderName("Order AA");
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(order));
        Carton carton = new Carton();
        carton.setId("c1"); carton.setPoId("p1"); carton.setOrderId("order1");
        carton.setBuyerCode(AppConstants.Buyer.LULULEMON); carton.setFactoryCode("F1");
        carton.setRfidStatus("PASS"); carton.setWeightStatus("PASS");
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(List.of(carton));
        when(mongo.find(any(Query.class), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(List.of());
    }

    @Test void optionTwoAdminSeesAssignedOnlyInEveryChart() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(actor(AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED, List.of("F1")));
        fixtures(mongo);
        ManagementReportService service = new ManagementReportService(mongo, access);
        var result = service.dashboardSummary(range(""));
        assertEquals(List.of("F1"), result.availableFactories());
        assertEquals(1, result.factoryPerformance().size());
        assertEquals("F1", result.factoryPerformance().get(0).factoryCode());
        assertEquals(10, result.shippingTrend().get(0).plannedCartons());
        assertEquals(1, result.qcStatus().finalPass());
        assertEquals(9, result.qcStatus().pending());
        assertThrows(AccessDeniedException.class, () -> service.dashboardSummary(range("F2")));
    }

    @Test void optionOneAdminWithNoAssignedFactoriesSeesAllFactories() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(actor(AppConstants.Permission.LULU_MANAGEMENT_REPORT_ALL, List.of()));
        fixtures(mongo);
        var result = new ManagementReportService(mongo, access).dashboardSummary(range(""));
        assertEquals(2, result.factoryPerformance().size());
        assertEquals(30, result.shippingTrend().get(0).plannedCartons());
        assertEquals(29, result.qcStatus().pending());
    }

    @Test void sixFiltersAreAppliedToEveryDashboardChart() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(actor(AppConstants.Permission.LULU_MANAGEMENT_REPORT_ALL, List.of()));
        fixtures(mongo);
        ManagementReportService service = new ManagementReportService(mongo, access);
        var matching = new ManagementReportService.Filters(LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 30), "F1", "2026-11-12", "order1", "IN_PROGRESS");
        var result = service.dashboardSummary(matching);
        assertEquals(1, result.factoryPerformance().size());
        assertEquals(10, result.shippingTrend().get(0).plannedCartons());
        assertEquals(1, result.qcStatus().finalPass());

        var wrongShipping = new ManagementReportService.Filters(matching.from(), matching.to(),
                "F1", "2026-11-13", "order1", "IN_PROGRESS");
        var empty = service.dashboardSummary(wrongShipping);
        assertTrue(empty.shippingTrend().isEmpty());
        assertTrue(empty.factoryPerformance().isEmpty());
        assertEquals(0, empty.qcStatus().finalPass());
        var wrongStatus = new ManagementReportService.Filters(matching.from(), matching.to(),
                "F1", "2026-11-12", "order1", "COMPLETED");
        assertTrue(service.dashboardSummary(wrongStatus).shippingTrend().isEmpty());
    }

    @Test void linkedFilterOptionsRespectAdminOptionTwoAndIgnoreActiveSwitchFactory() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        when(access.currentUser()).thenReturn(actor(AppConstants.Permission.LULU_MANAGEMENT_REPORT_ASSIGNED, List.of("F1")));
        fixtures(mongo);
        ManagementReportService service = new ManagementReportService(mongo, access);
        var options = service.dashboardOptions(range(""));
        assertEquals(List.of("F1"), options.factories());
        assertEquals(List.of("2026-11-12"), options.shippingDates());
        assertEquals("order1", options.orders().get(0).orderId());
        assertThrows(AccessDeniedException.class, () -> service.dashboardOptions(range("F2")));
    }

    @Test void noAccessHidesDataEvenForAdmin() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        User admin = actor(AppConstants.Permission.LULU_UPLOAD_EDIT_DATA, List.of("F1"));
        when(access.currentUser()).thenReturn(admin);
        ManagementReportService service = new ManagementReportService(mongo, access);
        assertThrows(AccessDeniedException.class, () -> service.dashboardSummary(range("")));
        assertThrows(AccessDeniedException.class, () -> service.dashboardOptions(range("")));
        verifyNoInteractions(mongo);
    }
}
