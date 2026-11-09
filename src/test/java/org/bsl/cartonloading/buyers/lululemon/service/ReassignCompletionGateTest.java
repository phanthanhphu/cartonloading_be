package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Re-assign is blocked by completed RFID + Weight, not by activating an unfinished carton. */
class ReassignCompletionGateTest {
    private Carton carton(String rfid, String weight, String state) {
        Carton c = new Carton();
        c.setRfidStatus(rfid);
        c.setWeightStatus(weight);
        c.setStatus(state);
        return c;
    }

    @Test void mayReassignReadyToPackCarton() {
        assertFalse(WorkflowService.isCompletedForIdentityReassign(
                carton(null, null, AppConstants.CartonStatus.READY_TO_PACK)));
    }

    @Test void mayReassignActivatedCartonWithoutScans() {
        assertFalse(WorkflowService.isCompletedForIdentityReassign(
                carton("SCANNING", null, AppConstants.CartonStatus.PACKING)));
    }

    @Test void mayReassignCartonWithRfidResultButNoWeight() {
        assertFalse(WorkflowService.isCompletedForIdentityReassign(
                carton(AppConstants.RfidCheck.PASS, null, AppConstants.CartonStatus.LABEL_CONFIRMED)));
        assertFalse(WorkflowService.isCompletedForIdentityReassign(
                carton(AppConstants.RfidCheck.FAILED, null, AppConstants.CartonStatus.READY_TO_PACK)));
    }

    @Test void neverReassignCompletedPassOrFailedCarton() {
        assertTrue(WorkflowService.isCompletedForIdentityReassign(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.PASS,
                        AppConstants.CartonStatus.READY_TO_SHIP)));
        assertTrue(WorkflowService.isCompletedForIdentityReassign(
                carton(AppConstants.RfidCheck.FAILED, AppConstants.WeightStatus.PASS,
                        AppConstants.CartonStatus.READY_TO_PACK)));
        assertTrue(WorkflowService.isCompletedForIdentityReassign(
                carton(AppConstants.RfidCheck.PASS, AppConstants.WeightStatus.FAILED,
                        AppConstants.CartonStatus.READY_TO_PACK)));
    }
}
