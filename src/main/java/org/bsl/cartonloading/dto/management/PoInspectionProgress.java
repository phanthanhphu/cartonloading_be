package org.bsl.cartonloading.dto.management;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.util.List;

/** Read-only inspection progress. Planned cartons not yet generated still count as pending. */
public record PoInspectionProgress(
        String status,
        int plannedCartons,
        int generatedCartons,
        int rfidPassedCartons,
        int weightPassedCartons,
        int rfidFailedCartons,
        int weightFailedCartons,
        boolean rfidComplete,
        boolean weightComplete
) {
    public static PoInspectionProgress fromCartons(PurchaseOrder po, List<Carton> cartons) {
        return fromCounts(po, cartons.size(),
                (int) cartons.stream().filter(c -> "PASS".equals(c.getRfidStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())).count(),
                (int) cartons.stream().filter(c -> "FAILED".equals(c.getRfidStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.WeightStatus.FAILED.equals(c.getWeightStatus()) || "HOLD".equals(c.getWeightStatus())).count());
    }

    public static PoInspectionProgress fromCounts(PurchaseOrder po, int generated, int rfidPassed, int weightPassed,
                                                   int rfidFailed, int weightFailed) {
        int planned = Math.max(0, po.getCartonCount() == null ? 0 : po.getCartonCount());
        // Count only 100%-PASS cartons. A FAILED inspection is NOT completed successfully.
        boolean rfidOk = planned > 0 && generated == planned && rfidPassed == planned;
        boolean weightOk = planned > 0 && generated == planned && weightPassed == planned;
        String status = "IMPORTING".equalsIgnoreCase(po.getStatus()) ? "IMPORTING"
                : rfidOk && weightOk ? "COMPLETED" : "INCOMPLETE";
        return new PoInspectionProgress(status, planned, generated, rfidPassed, weightPassed,
                rfidFailed, weightFailed, rfidOk, weightOk);
    }
}
