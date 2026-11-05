package org.bsl.cartonloading.common.constants;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.model.SystemType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** String contracts must not change when hardcoded literals are centralized. */
class AppConstantsContractTest {
    @Test
    void buyerSystemTypeValuesStayCompatible() {
        assertEquals("LULULEMON", AppConstants.Buyer.LULULEMON);
        assertEquals("ENGELBERT_STRAUSS", AppConstants.Buyer.ENGELBERT_STRAUSS);
        assertEquals("RFID", SystemType.RFID);
        assertEquals("UNRFID", SystemType.UNRFID);
        assertEquals("FACTORY_USE_ONLY", SystemType.FACTORY_USE_ONLY);
    }

    @Test
    void rfidVerdictIsDistinctFromLifecycleAndQcStatus() {
        assertEquals("PASS", AppConstants.RfidCheck.PASS);
        assertEquals("FAILED", AppConstants.RfidCheck.FAILED);
        assertEquals("VERIFIED", AppConstants.RfidStatus.VERIFIED);
        assertEquals("FAIL", AppConstants.CheckingResult.FAIL);
    }

    @Test
    void finalCartonStatusesKeepExactWireValuesAndBusinessRules() {
        Carton carton = new Carton();
        assertEquals("WAITING_RFID", carton.getFinalCheckStatus());

        carton.setRfidStatus(AppConstants.RfidCheck.FAILED);
        assertEquals("RFID_HOLD", carton.getFinalCheckStatus());

        carton.setRfidStatus(AppConstants.RfidCheck.PASS);
        assertEquals("WAITING_WEIGHT", carton.getFinalCheckStatus());

        carton.setWeightStatus(AppConstants.WeightStatus.HOLD);
        assertEquals("WEIGHT_FAILED", carton.getFinalCheckStatus());

        carton.setWeightStatus(AppConstants.WeightStatus.PASS);
        assertEquals("FINAL_PASS", carton.getFinalCheckStatus());
    }
}
