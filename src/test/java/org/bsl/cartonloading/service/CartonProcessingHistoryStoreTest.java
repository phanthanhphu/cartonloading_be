package org.bsl.cartonloading.service;

import org.bsl.cartonloading.model.CartonProcessingHistory;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Regression cases: buyer partitioning, idempotent identity and completed-only persistence. */
class CartonProcessingHistoryStoreTest {
    @Test void sameCartonInDifferentBuyersCannotShareHistoryRecord() {
        assertNotEquals(CartonProcessingHistory.key("LULULEMON", "carton1"),
                CartonProcessingHistory.key("OTHER_BUYER", "carton1"));
    }

    @Test void repeatedSaveUsesStableBuyerCartonId() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.save(any(CartonProcessingHistory.class))).thenAnswer(inv -> inv.getArgument(0));
        CartonProcessingHistoryStore store = new CartonProcessingHistoryStore(mongo);
        CartonProcessingHistory row = new CartonProcessingHistory();
        row.setBuyerCode("LULULEMON"); row.setCartonId("c123");
        row.setStatus("FAILED"); row.setWeightTime(LocalDateTime.now());
        assertEquals("LULULEMON:c123", store.save(row).getId());
        row.setStatus("PASS");
        assertEquals("LULULEMON:c123", store.save(row).getId());
        verify(mongo, times(2)).save(any(CartonProcessingHistory.class));
    }

    @Test void pendingCartonCannotBeWritten() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        CartonProcessingHistory row = new CartonProcessingHistory();
        row.setBuyerCode("LULULEMON"); row.setCartonId("c123");
        row.setStatus("PENDING"); row.setWeightTime(LocalDateTime.now());
        assertThrows(IllegalArgumentException.class,
                () -> new CartonProcessingHistoryStore(mongo).save(row));
        verifyNoInteractions(mongo);
    }
}
