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
        boolean weightComplete,
        int processedCartons,
        String finalResult,
        boolean ended
) {
    public static PoInspectionProgress fromCartons(PurchaseOrder po, List<Carton> cartons) {
        return fromCounts(po, cartons.size(),
                (int) cartons.stream().filter(c -> AppConstants.RfidCheck.PASS.equals(c.getRfidStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.RfidCheck.FAILED.equals(c.getRfidStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.WeightStatus.FAILED.equals(c.getWeightStatus()) || AppConstants.WeightStatus.HOLD.equals(c.getWeightStatus())).count(),
                (int) cartons.stream().filter(c -> AppConstants.WeightStatus.HOLD.equals(c.getWeightStatus())).count(),
                (int) cartons.stream().filter(c -> (AppConstants.RfidCheck.PASS.equals(c.getRfidStatus())
                                || AppConstants.RfidCheck.FAILED.equals(c.getRfidStatus()))
                        && (AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())
                                || AppConstants.WeightStatus.FAILED.equals(c.getWeightStatus()))).count());
    }

    /** Legacy counts lack a paired-carton total. Report only fully provable completion. */
    public static PoInspectionProgress fromCounts(PurchaseOrder po, int generated, int rfidPassed, int weightPassed,
                                                   int rfidFailed, int weightFailed) {
        return fromCounts(po, generated, rfidPassed, weightPassed, rfidFailed, weightFailed, 0,
                rfidPassed + rfidFailed == generated && weightPassed + weightFailed == generated ? generated : 0);
    }

    public static PoInspectionProgress fromCounts(PurchaseOrder po, int generated, int rfidPassed, int weightPassed,
                                                   int rfidFailed, int weightFailed, int weightHold, int pairedProcessed) {
        int planned = Math.max(0, po.getCartonCount() == null ? 0 : po.getCartonCount());
        // Count only 100%-PASS cartons. A FAILED inspection is NOT completed successfully.
        boolean rfidOk = planned > 0 && generated == planned && rfidPassed == planned;
        boolean weightOk = planned > 0 && generated == planned && weightPassed == planned;
        String status = "IMPORTING".equalsIgnoreCase(po.getStatus()) ? "IMPORTING"
                : rfidOk && weightOk ? "COMPLETED" : "INCOMPLETE";
        // Every carton must have BOTH independent results; only then is PO FINAL.
        // Paired count is sourced from the same Carton row (never min of unrelated counts).
        int processed = Math.max(0, Math.min(generated, pairedProcessed));
        boolean allProcessed = planned > 0 && generated == planned && processed == planned
                && rfidPassed + rfidFailed == planned && weightPassed + (weightFailed - weightHold) == planned;
        String finalResult = !allProcessed ? "PENDING"
                : rfidFailed > 0 || weightFailed > 0 ? "FAILED" : "PASS";
        boolean ended = allProcessed && po.getCartonProcessingEndedAt() != null
                && finalResult.equals(po.getCartonProcessingEndResult());
        return new PoInspectionProgress(status, planned, generated, rfidPassed, weightPassed,
                rfidFailed, weightFailed, rfidOk, weightOk,
                processed,
                finalResult, ended);
    }
}
