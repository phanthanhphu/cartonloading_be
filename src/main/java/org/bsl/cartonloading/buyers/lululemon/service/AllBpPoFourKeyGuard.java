package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.importing.TextNormalizer;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LULULEMON ALL_BP identity: (Master PO, PO, Packing Plan, SO (PTS)),
 * scoped to a single Order. The caller checks all rows and stored POs
 * before issuing any database writes, including shared uploads.
 */
final class AllBpPoFourKeyGuard {
    private AllBpPoFourKeyGuard() { }

    record Key(String masterPo, String poNumber, String packingPlan, String salesOrderPts) { }

    static Key key(String masterPo, String poNumber, String packingPlan, String salesOrderPts) {
        // Normalize case/whitespace for comparison only; preserve displayed values.
        return new Key(normalize(masterPo), normalize(poNumber),
                normalize(packingPlan), normalize(salesOrderPts));
    }

    static void checkFileRow(Map<Key, Integer> seen, String masterPo, String poNumber,
                             String packingPlan, String salesOrderPts, int rowNo) {
        Integer firstRow = seen.putIfAbsent(key(masterPo, poNumber, packingPlan, salesOrderPts), rowNo);
        if (firstRow != null) {
            throw new IllegalArgumentException(AppMessages.duplicateAllBpFourKeyInFile(
                    label(masterPo), label(poNumber), label(packingPlan), label(salesOrderPts), firstRow, rowNo));
        }
    }

    static void checkAgainstSaved(Collection<PurchaseOrder> saved, Set<Key> imported) {
        for (PurchaseOrder po : saved) {
            if (imported.contains(key(po.getMasterPo(), po.getPoNumber(),
                    po.getPackingPlan(), po.getSalesOrderPts()))) {
                throw new IllegalArgumentException(AppMessages.duplicateAllBpFourKeyAlreadySaved(
                        label(po.getMasterPo()), label(po.getPoNumber()),
                        label(po.getPackingPlan()), label(po.getSalesOrderPts())));
            }
        }
    }

    static void checkEditAgainstSaved(Collection<PurchaseOrder> candidates, String editingPoId,
                                      String masterPo, String poNumber, String packingPlan,
                                      String salesOrderPts) {
        Key desired = key(masterPo, poNumber, packingPlan, salesOrderPts);
        for (PurchaseOrder candidate : candidates) {
            if (!Objects.equals(editingPoId, candidate.getId())
                    && desired.equals(key(candidate.getMasterPo(), candidate.getPoNumber(),
                            candidate.getPackingPlan(), candidate.getSalesOrderPts()))) {
                throw new IllegalArgumentException(AppMessages.duplicateAllBpFourKeyOnEdit(
                        label(masterPo), label(poNumber), label(packingPlan), label(salesOrderPts)));
            }
        }
    }

    private static String normalize(String value) {
        return Objects.toString(TextNormalizer.key(value), "");
    }

    private static String label(String value) {
        return Objects.toString(TextNormalizer.trimToNull(value), "(blank)");
    }
}
