package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.shared.orders.model.OrderDocument;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Import is the source of truth; Shipping Schedule must never create/double the PO. */
class ManagementReportImportedOverviewTest {
    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    private static ManagementReportService.Filters scope(String basis) {
        return new ManagementReportService.Filters(FROM, TO, "", "", "", "", basis);
    }

    private static PurchaseOrder po(String id, String factory, int count, LocalDateTime importAt, LocalDate... dates) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id); po.setBuyerCode(AppConstants.Buyer.LULULEMON);
        po.setOrderId("order1"); po.setPoNumber(id); po.setFactoryCode(factory);
        po.setPlannedCartons(count); po.setCreatedAt(importAt);
        po.setShippingDates(List.of(dates));
        return po;
    }

    private static ManagementReportService fixture(MongoTemplate mongo, BuyerFactoryAccessService access,
                                                   List<PurchaseOrder> pos, List<Carton> cartons) {
        User user = mock(User.class);
        when(access.currentUser()).thenReturn(user);
        when(user.isEnabled()).thenReturn(true);
        when(user.canLululemonViewManagementReports()).thenReturn(true);
        when(user.canLululemonReportAllFactories()).thenReturn(true);
        when(mongo.find(any(Query.class), eq(PurchaseOrder.class))).thenReturn(new ArrayList<>(pos));
        OrderDocument parent = new OrderDocument();
        parent.setId("order1"); parent.setOrderName("Imported 1");
        parent.setBuyerCode(AppConstants.Buyer.LULULEMON);
        parent.setCreatedAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        when(mongo.find(any(Query.class), eq(OrderDocument.class))).thenReturn(List.of(parent));
        when(mongo.find(any(Query.class), eq(Carton.class))).thenReturn(cartons);
        when(mongo.find(any(Query.class), eq(Document.class), eq(AppConstants.Collection.PO_SHIPPING_DAY_SUBMISSIONS)))
                .thenReturn(List.of());
        return new ManagementReportService(mongo, access);
    }

    @Test void importWithoutShippingIsImmediatelyVisibleInReportAndDashboard() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var one = po("p1", "F3", 8, LocalDateTime.of(2026, 11, 10, 10, 0));
        var two = po("p2", "F3", 12, LocalDateTime.of(2026, 11, 10, 11, 0));
        var service = fixture(mongo, access, List.of(one, two), List.of());
        var result = service.report(scope("ALL"), 0, 25);
        assertEquals(2, result.totalRows());
        assertEquals(1, result.totals().orders());
        assertEquals(2, result.totals().uniquePos());
        assertEquals(20, result.totals().plannedCartons());
        assertEquals(20, result.totals().pending());
        assertEquals(0, result.totals().shippingDays());
        assertTrue(result.pos().stream().allMatch(r -> "UNSCHEDULED".equals(r.shippingStatus())));
        assertEquals(1, result.options().orders().size());
        assertTrue(result.options().shippingDates().isEmpty());
        var summary = service.dashboardSummary(scope("ALL"));
        assertEquals(2, summary.overview().pos());
        assertEquals(2, summary.overview().unscheduledPos());
        assertEquals(20, summary.overview().plannedCartons());
        assertTrue(summary.shippingTrend().isEmpty());
        assertEquals(20, summary.qcStatus().pending());
        assertEquals(1, service.dashboardOptions(scope("ALL")).orders().size());
        assertEquals(0, service.report(scope("SHIPPING"), 0, 25).totalRows());
    }

    @Test void twoShippingDatesProduceTwoMembershipRowsButOnePoAndCartonTotals() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var one = po("p1", "F3", 10, LocalDateTime.of(2026, 11, 8, 10, 0),
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 28));
        Carton carton = new Carton();
        carton.setId("c1"); carton.setBuyerCode(AppConstants.Buyer.LULULEMON);
        carton.setOrderId("order1"); carton.setPoId("p1"); carton.setFactoryCode("F3");
        carton.setRfidStatus("PASS"); carton.setWeightStatus("PASS");
        var service = fixture(mongo, access, List.of(one), List.of(carton));
        var all = service.report(scope("ALL"), 0, 25);
        var shipping = service.report(scope("SHIPPING"), 0, 25);
        assertEquals(1, all.totalRows());
        assertEquals(2, shipping.totalRows());
        assertEquals(1, shipping.totals().uniquePos());
        assertEquals(2, shipping.totals().poAssignments());
        assertEquals(10, shipping.totals().plannedCartons());
        assertEquals(1, shipping.totals().finalPass());
        assertEquals(9, shipping.totals().pending());
        assertEquals(10, service.dashboardSummary(scope("ALL")).overview().plannedCartons());
        assertEquals(1, service.dashboardSummary(scope("ALL")).overview().scheduledPos());
    }

    @Test void importedDateUsesActualImportTimestampNotShippingDate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var old = po("p1", "F3", 3, LocalDateTime.of(2026, 10, 12, 10, 0));
        var newPo = po("p2", "F3", 7, LocalDateTime.of(2026, 11, 10, 10, 0));
        var service = fixture(mongo, access, List.of(old, newPo), List.of());
        var report = service.report(scope("IMPORTED"), 0, 25);
        assertEquals(1, report.totalRows());
        assertEquals("p2", report.pos().get(0).poId());
        assertEquals(7, report.totals().plannedCartons());
        assertEquals(2, service.report(scope("ALL"), 0, 25).totalRows());
    }

    @Test void productionChartUsesSavedChecksEvenWithoutShippingSchedule() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var one = po("p1", "F3", 10, LocalDateTime.of(2026, 11, 9, 8, 0));
        Carton pass = new Carton();
        pass.setId("c1"); pass.setBuyerCode(AppConstants.Buyer.LULULEMON);
        pass.setPoId("p1"); pass.setOrderId("order1"); pass.setFactoryCode("F3");
        pass.setRfidStatus("PASS"); pass.setWeightStatus("PASS");
        pass.setRfidVerifiedAt(LocalDateTime.of(2026, 11, 10, 10, 0));
        pass.setWeighedAt(LocalDateTime.of(2026, 11, 11, 11, 0));
        Carton fail = new Carton();
        fail.setId("c2"); fail.setBuyerCode(AppConstants.Buyer.LULULEMON);
        fail.setPoId("p1"); fail.setOrderId("order1"); fail.setFactoryCode("F3");
        fail.setRfidStatus("FAILED"); fail.setWeightStatus("PASS");
        fail.setRfidVerifiedAt(LocalDateTime.of(2026, 11, 11, 12, 0));
        fail.setWeighedAt(LocalDateTime.of(2026, 11, 11, 13, 0));
        var summary = fixture(mongo, access, List.of(one), List.of(pass, fail)).dashboardSummary(scope("ALL"));
        assertTrue(summary.shippingTrend().isEmpty());
        assertEquals(2, summary.productionTrend().size());
        assertEquals("2026-11-10", summary.productionTrend().get(0).date());
        assertEquals(1, summary.productionTrend().get(0).rfidPass());
        var day11 = summary.productionTrend().get(1);
        assertEquals("2026-11-11", day11.date());
        assertEquals(1, day11.rfidFailed());
        assertEquals(2, day11.weightPass());
        assertEquals(1, day11.finalPass());
        assertEquals(1, day11.finalFailed());
        assertEquals(1, summary.overview().finalPass());
        assertEquals(1, summary.overview().finalFailed());
        assertEquals(8, summary.overview().pending());
    }

    @Test void productionChartCountsMultiShippingPOOnceAndRespectsDateWindow() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var one = po("p1", "F3", 10, LocalDateTime.of(2026, 11, 9, 8, 0),
                LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 28));
        Carton pass = new Carton();
        pass.setId("c1"); pass.setBuyerCode(AppConstants.Buyer.LULULEMON);
        pass.setPoId("p1"); pass.setOrderId("order1"); pass.setFactoryCode("F3");
        pass.setRfidStatus("PASS"); pass.setWeightStatus("PASS");
        pass.setRfidVerifiedAt(LocalDateTime.of(2026, 11, 10, 10, 0));
        pass.setWeighedAt(LocalDateTime.of(2026, 11, 11, 11, 0));
        var service = fixture(mongo, access, List.of(one), List.of(pass));
        var summary = service.dashboardSummary(scope("SHIPPING"));
        assertEquals(2, summary.shippingTrend().size());
        assertEquals(2, summary.productionTrend().size());
        assertEquals(1, summary.productionTrend().stream().mapToLong(p -> p.finalPass()).sum());
        var outside = new ManagementReportService.Filters(LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 11, 30), "", "", "", "", "ALL");
        assertTrue(service.dashboardSummary(outside).productionTrend().isEmpty());
    }

    @Test void unscheduledPoDetailsAreReadableWithoutShippingDate() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
        var one = po("p1", "F3", 5, LocalDateTime.of(2026, 11, 10, 10, 0));
        var service = fixture(mongo, access, List.of(one), List.of());
        var detail = service.poDetail(scope("ALL"), "p1", "", 0, 25);
        assertEquals("UNSCHEDULED", detail.po().shippingStatus());
        assertEquals(0, detail.totalCartons());
    }
}
