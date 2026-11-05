package org.bsl.cartonloading.buyers.lululemon.service;

import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.PoShippingPlanRequest;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.bsl.cartonloading.service.SelectedFactoryScope;
import org.bsl.cartonloading.security.AccessControl;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PoShippingDaysTest {
    private static final String BUYER = "LULULEMON";
    private static final String ORDER = "order-1";
    private final PackingOrderService orders = mock(PackingOrderService.class);
    private final PurchaseOrderRepository pos = mock(PurchaseOrderRepository.class);
    private final BuyerFactoryAccessService access = mock(BuyerFactoryAccessService.class);
    private final FactoryCatalogService factories = mock(FactoryCatalogService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final SelectedFactoryScope selected = mock(SelectedFactoryScope.class);
    private final AccessControl accessControl = mock(AccessControl.class);
    private final PoShippingPlanService service = new PoShippingPlanService(orders, pos, access, factories, mongo, audit, selected, accessControl);

    private PurchaseOrder po(String id, String number, LocalDate legacy, LocalDate... extra) {
        PurchaseOrder po = new PurchaseOrder();
        po.setId(id); po.setOrderId(ORDER); po.setBuyerCode(BUYER);
        po.setFactoryCode("F3"); po.setPoNumber(number);
        po.setPlannedShippingDate(legacy); po.setShippingDates(List.of(extra));
        return po;
    }

    @Test void groupsDifferentPosByOneDateAndAllowsPoInMultipleDates() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER)).thenReturn(List.of(
                po("a", "1001", LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 15)),
                po("b", "1002", LocalDate.of(2026, 11, 15))));
        var rows = service.list(BUYER, ORDER, "F3");
        assertEquals(2, rows.size());
        assertEquals("2026-11-10", rows.get(0).shippingDate());
        assertEquals(1, rows.get(0).poCount());
        assertEquals("2026-11-15", rows.get(1).shippingDate());
        assertEquals(2, rows.get(1).poCount());
    }

    @Test void alreadyScheduledPoCannotBeAddedToAnotherDate() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER))
                .thenReturn(Optional.of(po("a", "1001", LocalDate.of(2026, 11, 10))));
        var error = assertThrows(ResponseStatusException.class, () -> service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(LocalDate.of(2026, 11, 15), List.of("a"))));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(mongo);
    }

    @Test void alreadyScheduledPoWithArrayOnlyCannotBeAddedToAnotherDate() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER))
                .thenReturn(Optional.of(po("a", "1001", null, LocalDate.of(2026, 11, 10))));
        assertThrows(ResponseStatusException.class, () -> service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(LocalDate.of(2026, 11, 15), List.of("a"))));
        verifyNoInteractions(mongo);
    }

    @Test void unassignedPoCanBeAddedOnce() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER))
                .thenReturn(Optional.of(po("a", "1001", null)));
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(PurchaseOrder.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1, null));
        when(accessControl.canCreateShippingSchedule()).thenReturn(true);
        var result = service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10), List.of("a")));
        assertEquals(1L, result.get("scheduledCount"));
        verify(mongo, times(2)).updateMulti(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }

    @Test void newScheduleRequiresExplicitCreateGrant() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER))
                .thenReturn(Optional.of(po("a", "1001", null)));
        when(accessControl.canManageShipping()).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5), List.of("a"))));
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }

    @Test void createGrantCannotModifyExistingScheduleWithoutSchedulerGrant() {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5);
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("new", ORDER, BUYER))
                .thenReturn(Optional.of(po("new", "1002", null)));
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER))
                .thenReturn(List.of(po("old", "1001", day)));
        when(accessControl.canCreateShippingSchedule()).thenReturn(true);
        assertThrows(AccessDeniedException.class, () -> service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(day, List.of("new"))));
        verify(mongo, never()).updateMulti(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }

    @Test void schedulerCanStillAddToExistingDateWithoutCreateGrant() {
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(5);
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("new", ORDER, BUYER))
                .thenReturn(Optional.of(po("new", "1002", null)));
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER))
                .thenReturn(List.of(po("old", "1001", day)));
        when(accessControl.canManageShipping()).thenReturn(true);
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(PurchaseOrder.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1, null));
        var response = service.schedule(BUYER, ORDER, new PoShippingPlanRequest(day, List.of("new")));
        assertEquals(1L, response.get("scheduledCount"));
    }

    @Test void duplicatePoOnSameDateIsRejectedWithoutAnyWrites() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.requested(BUYER)).thenReturn("F3");
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER))
                .thenReturn(Optional.of(po("a", "1001", LocalDate.of(2026, 11, 15))));
        assertThrows(ResponseStatusException.class, () -> service.schedule(BUYER, ORDER,
                new PoShippingPlanRequest(LocalDate.of(2026, 11, 15), List.of("a"))));
        verifyNoInteractions(mongo);
    }

    @Test void cannotDeleteWholeScheduleAfterShippingDate() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate yesterday = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
        assertThrows(ResponseStatusException.class,
                () -> service.deleteDay(BUYER, ORDER, "F3", yesterday));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }

    @Test void deleteFutureDateOnlyRemovesShippingMembershipsNotPos() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(1);
        LocalDate otherDate = today.plusDays(2);
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER)).thenReturn(List.of(
                po("a", "1001", today, otherDate), po("b", "1002", today)));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1, null));
        var result = service.deleteDay(BUYER, ORDER, "F3", today);
        assertEquals(2, result.get("removedCount"));
        verify(mongo, times(2)).updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
        verify(pos, never()).deleteById(anyString());
    }


    @Test void automaticallySubmitsShippingDateInVietnamAndLocksEdits() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        PurchaseOrder current = po("a", "1001", day);
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER)).thenReturn(List.of(current));
        var days = service.list(BUYER, ORDER, "F3");
        assertEquals(1, days.size());
        assertTrue(days.get(0).submitted());
        assertTrue(days.get(0).autoSubmitted());
        assertEquals("SYSTEM", days.get(0).submittedBy());
        var detail = service.detail(BUYER, ORDER, "F3", day);
        assertTrue(detail.submitted());
        assertThrows(ResponseStatusException.class, () -> service.deleteDay(BUYER, ORDER, "F3", day));
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }

    @Test void manualSubmitPersistsLockEvenBeforeScheduledDay() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(2);
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER))
                .thenReturn(List.of(po("a", "1001", day)));
        when(mongo.findById(anyString(), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(null, new Document("state", "SUBMITTED")
                        .append("submittedBy", "operator").append("submittedAt", "2026-11-04T09:00:00"));
        var response = service.submitDay(BUYER, ORDER, "F3", day);
        assertTrue(response.submitted());
        assertFalse(response.autoSubmitted());
        verify(mongo).upsert(any(Query.class), any(Update.class), eq("po_shipping_day_submissions"));
    }

    @Test void userWithoutReopenGrantCannotUnsubmit() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        assertThrows(AccessDeniedException.class, () -> service.unsubmitDay(BUYER, ORDER, "F3", day));
        verify(mongo, never()).upsert(any(Query.class), any(Update.class), anyString());
    }

    @Test void grantedUserCanReopenPastDateAndItStaysEditable() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate day = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).minusDays(1);
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER))
                .thenReturn(List.of(po("a", "1001", day)));
        when(mongo.findById(anyString(), eq(Document.class), eq("po_shipping_day_submissions")))
                .thenReturn(null, new Document("state", "REOPENED"));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1, null));
        when(accessControl.canReopenShippingSchedule()).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "supervisor", "", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            var response = service.unsubmitDay(BUYER, ORDER, "F3", day);
            assertFalse(response.submitted());
            assertTrue(response.reopened());
            verify(mongo).upsert(any(Query.class), any(Update.class), eq("po_shipping_day_submissions"));
            // The reopened overdue date is genuinely editable (not immediately auto-locked).
            var deleted = service.deleteDay(BUYER, ORDER, "F3", day);
            assertEquals(1, deleted.get("removedCount"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test void removalOnlyRemovesMembershipWithoutDeletingOriginalPo() {
        when(orders.getEntity(BUYER, ORDER)).thenReturn(new PackingOrder());
        when(selected.constrain(BUYER, "F3")).thenReturn("F3");
        LocalDate assigned = LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")).plusDays(10);
        PurchaseOrder po = po("a", "1001", assigned, assigned.plusDays(5));
        when(pos.findByIdAndOrderIdAndBuyerCode("a", ORDER, BUYER)).thenReturn(Optional.of(po));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1, null));
        when(pos.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(ORDER, BUYER)).thenReturn(List.of());
        var result = service.removePo(BUYER, ORDER, "F3", assigned, "a");
        assertTrue(result.pos().isEmpty());
        verify(pos, never()).deleteById(anyString());
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PurchaseOrder.class));
    }
}
