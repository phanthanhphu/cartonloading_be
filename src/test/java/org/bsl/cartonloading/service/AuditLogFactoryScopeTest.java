package org.bsl.cartonloading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bsl.cartonloading.model.AuditLog;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.repository.AuditLogRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.bsl.cartonloading.security.WorkspaceSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Audit must remain Factory-scoped at the API, not just in the table filter. */
class AuditLogFactoryScopeTest {
    private final AuditLogRepository records = mock(AuditLogRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final AuditLogService subject = new AuditLogService(records, users, mongo, new ObjectMapper());

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    private void signIn(boolean admin, List<String> assigned) {
        signIn(admin, assigned, "LULULEMON", "RFID");
    }

    private void signIn(boolean admin, List<String> assigned, String buyer, String type) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getName()).thenReturn("person@example.test");
        when(auth.getDetails()).thenReturn(new WorkspaceSession(buyer, type));
        SecurityContextHolder.getContext().setAuthentication(auth);
        User user = mock(User.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.canAccessBuyerType(buyer, type)).thenReturn(true);
        when(user.isAdminRole()).thenReturn(admin);
        if (!admin) when(user.getFactoryPermissions()).thenReturn(assigned);
        when(users.findByEmail("person@example.test")).thenReturn(Optional.of(user));
        when(mongo.find(any(Query.class), eq(AuditLog.class))).thenReturn(List.of());
    }

    private void search(String factory) {
        subject.search(null, null, null, null, null, null, factory, null, null, null, 0, 20);
    }

    @Test void legacyPoActionNowPersistsItsAuthoritativeFactory() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-1"); po.setBuyerCode("LULULEMON"); po.setFactoryCode("F3");
        when(mongo.findById("po-1", org.bsl.cartonloading.buyers.lululemon.model.Carton.class)).thenReturn(null);
        when(mongo.findById("po-1", PurchaseOrder.class)).thenReturn(po);
        subject.log("operator", "PO_UPDATED", AppConstants.AuditResource.CARTON_LOADING,
                "po-1", "PO changed", null, null);
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(records).save(captured.capture());
        assertEquals("LULULEMON", captured.getValue().getBuyerCode());
        assertEquals("F3", captured.getValue().getFactoryCode());
    }

    @Test void explicitBuyerMismatchNeverBorrowsAnotherBuyersFactory() {
        PurchaseOrder po = new PurchaseOrder();
        po.setId("po-wrong-buyer"); po.setBuyerCode("LULULEMON"); po.setFactoryCode("F3");
        when(mongo.findById("po-wrong-buyer", PurchaseOrder.class)).thenReturn(po);
        subject.logChange("operator", "UPDATE", AppConstants.AuditResource.CARTON_LOADING,
                "po-wrong-buyer", "some event", "ENGELBERT_STRAUSS", null, null, null, null, null);
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(records).save(captured.capture());
        assertEquals("ENGELBERT_STRAUSS", captured.getValue().getBuyerCode());
        assertNull(captured.getValue().getFactoryCode());
    }

    @Test void unresolvableActionMustNotBorrowSelectedFactory() {
        subject.log("operator", "SOME_EVENT", "UNKNOWN_MODULE", "missing", "Some event", null, null);
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(records).save(captured.capture());
        assertNull(captured.getValue().getFactoryCode());
        assertNull(captured.getValue().getBuyerCode());
    }

    @Test void postDeleteAuditRetainsVerifiedFactory() {
        subject.logForFactory("operator", "PO_DELETE", AppConstants.AuditResource.CARTON_LOADING,
                "deleted-po", "Deleted PO", "LULULEMON", "F3");
        var captured = org.mockito.ArgumentCaptor.forClass(AuditLog.class);
        verify(records).save(captured.capture());
        assertEquals("LULULEMON", captured.getValue().getBuyerCode());
        assertEquals("F3", captured.getValue().getFactoryCode());
    }

    @Test void f3OperatorDefaultsToF3AndNeverReadsMissingFactory() {
        signIn(false, List.of("F3"));
        search(null);
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("factoryCode"));
        assertTrue(query.contains("F3"));
        assertTrue(query.contains("buyerCode"));
        assertTrue(query.contains("LULULEMON"));
        assertFalse(query.contains("$in"));
    }

    @Test void f3OperatorCannotQueryF2() {
        signIn(false, List.of("F3"));
        assertThrows(AccessDeniedException.class, () -> search("F2"));
        verifyNoInteractions(mongo);
    }

    @Test void multiFactoryOperatorMustChooseOneFactoryNotAll() {
        signIn(false, List.of("F3", "F4"));
        var error = assertThrows(ResponseStatusException.class, () -> search(null));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(mongo);
        search("F3");
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        assertTrue(captured.getValue().getQueryObject().toJson().contains("F3"));
    }

    @Test void f3OperatorCannotOpenF2OrMissingFactoryDetail() {
        signIn(false, List.of("F3"));
        AuditLog f2Log = new AuditLog();
        f2Log.setBuyerCode("LULULEMON"); f2Log.setFactoryCode("F2");
        when(records.findById("some-id")).thenReturn(Optional.of(f2Log));
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", null));
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", "F2"));
        f2Log.setFactoryCode(null);
        assertThrows(AccessDeniedException.class, () -> subject.getById("some-id", "F3"));
    }

    @Test void adminWithoutFiltersSeesOnlyActiveBuyer() {
        signIn(true, List.of());
        search(null);
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("buyerCode"));
        assertTrue(query.contains("LULULEMON"));
        assertFalse(query.contains("ENGELBERT_STRAUSS"));
    }

    @Test void adminCannotRequestOtherBuyerByQueryParameter() {
        signIn(true, List.of());
        assertThrows(AccessDeniedException.class,
                () -> subject.search(null, null, null, null, null, "ENGELBERT_STRAUSS", null,
                        null, null, null, 0, 20));
        verifyNoInteractions(mongo);
    }

    @Test void nonAdminCannotRequestOtherBuyerByQueryParameter() {
        signIn(false, List.of("F3"));
        assertThrows(AccessDeniedException.class,
                () -> subject.search(null, null, null, null, null, "ENGELBERT_STRAUSS", "F3",
                        null, null, null, 0, 20));
        verifyNoInteractions(mongo);
    }

    @Test void adminCannotReadAuditDetailOfAnotherBuyerOrGlobalLegacyLog() {
        signIn(true, List.of());
        AuditLog log = new AuditLog();
        log.setBuyerCode("ENGELBERT_STRAUSS"); log.setFactoryCode("F3");
        when(records.findById("foreign")).thenReturn(Optional.of(log));
        var forbidden = assertThrows(ResponseStatusException.class,
                () -> subject.getById("foreign", null));
        assertEquals(HttpStatus.NOT_FOUND, forbidden.getStatusCode());
        log.setBuyerCode(null);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
                () -> subject.getById("foreign", null)).getStatusCode());
        log.setBuyerCode("LULULEMON");
        assertSame(log, subject.getById("foreign", null));
    }

    @Test void esWorkspaceReadsOnlyEsBuyerRecordsEvenWhenAdmin() {
        signIn(true, List.of(), "ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        search(null);
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("ENGELBERT_STRAUSS"));
        assertFalse(query.contains("LULULEMON"));
        assertThrows(AccessDeniedException.class,
                () -> subject.search(null, null, null, null, null, "LULULEMON", null,
                        null, null, null, 0, 20));
    }

    @Test void adminFactoryFilterCannotRemoveBuyerConstraint() {
        signIn(true, List.of());
        search("F3");
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("LULULEMON"));
        assertTrue(query.contains("F3"));
    }

    @Test void operatorCannotOpenAnotherBuyersLogEvenWhenFactoryMatches() {
        signIn(false, List.of("F3"));
        AuditLog row = new AuditLog();
        row.setBuyerCode("ENGELBERT_STRAUSS"); row.setFactoryCode("F3");
        when(records.findById("other-buyer-log")).thenReturn(Optional.of(row));
        var blocked = assertThrows(ResponseStatusException.class,
                () -> subject.getById("other-buyer-log", "F3"));
        assertEquals(HttpStatus.NOT_FOUND, blocked.getStatusCode());
    }

    @Test void loggedInNonAdminEsUserMustStayInTheirFactoryAndBuyer() {
        signIn(false, List.of("F3"), "ENGELBERT_STRAUSS", "FACTORY_USE_ONLY");
        search("F3");
        var captured = org.mockito.ArgumentCaptor.forClass(Query.class);
        verify(mongo).count(captured.capture(), eq(AuditLog.class));
        String query = captured.getValue().getQueryObject().toJson();
        assertTrue(query.contains("ENGELBERT_STRAUSS"));
        assertTrue(query.contains("F3"));
    }
}
