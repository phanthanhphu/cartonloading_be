package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FactoryUsageServiceTest {
    @Test void checksAssignmentsAndOperationalCollectionsWithoutAuditAsBlocker() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.exists(any(Query.class), eq(AppConstants.Collection.USERS))).thenReturn(true);
        when(mongo.exists(any(Query.class), eq(AppConstants.Collection.PURCHASE_ORDERS))).thenReturn(true);
        List<String> usedBy = new FactoryUsageService(mongo).findUsage("F3");
        assertTrue(usedBy.contains("Users / Factory assignments"));
        assertTrue(usedBy.contains("Purchase Orders"));
        verify(mongo, never()).exists(any(Query.class), eq(AppConstants.Collection.AUDIT_LOGS));
        verify(mongo, never()).exists(any(Query.class), eq(AppConstants.Collection.FACTORIES));
    }

    @Test void unusedFactoryHasNoOperationalReferences() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        assertTrue(new FactoryUsageService(mongo).findUsage("F9").isEmpty());
    }
}
