package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/** History must show completed outcomes, never ALL_BP planned/Pending cartons. */
class CartonHistoryCompletionTest {
    private static Carton carton(String rfid, String weight, boolean hasWeighTime) {
        Carton carton = new Carton();
        carton.setRfidStatus(rfid);
        carton.setWeightStatus(weight);
        if (hasWeighTime) carton.setWeighedAt(LocalDateTime.of(2026, 11, 2, 9, 15));
        return carton;
    }

    @Test void importedCartonNeverAppearsInHistory() {
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton(null, null, false)));
    }

    @Test void rfidPassBeforeWeighIsNotHistoryYet() {
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton("PASS", null, false)));
    }

    @Test void rfidFailBeforeWeighIsNotHistoryYet() {
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton("FAILED", null, false)));
    }

    @Test void afterWeighPassWithRfidPassIsHistory() {
        assertTrue(WorkflowService.isCompletedHistoryCarton(carton("PASS", "PASS", true)));
    }

    @Test void afterWeighFailIsHistoryEvenWithRfidPass() {
        assertTrue(WorkflowService.isCompletedHistoryCarton(carton("PASS", "FAILED", true)));
    }

    @Test void afterWeighPassIsHistoryEvenWithRfidFailed() {
        assertTrue(WorkflowService.isCompletedHistoryCarton(carton("FAILED", "PASS", true)));
    }

    @Test void bothFailedAfterWeighIsHistory() {
        assertTrue(WorkflowService.isCompletedHistoryCarton(carton("FAILED", "FAILED", true)));
    }

    @Test void missingWeightCompletionTimeCannotEnterHistory() {
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton("PASS", "PASS", false)));
    }

    @Test void reopenedCartonIsNotCountedAsCompleted() {
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton("WAITING_RFID", "PASS", true)));
        assertFalse(WorkflowService.isCompletedHistoryCarton(carton("PASS", null, true)));
    }
}
