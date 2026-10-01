package org.bsl.cartonloading.dto.management;
import java.util.List;
public record DashboardSummary(long totalOrders, long totalPos, long totalCartons, long totalItems,
                               long assignedIdentities, long completedCartons, long weightWarnings,
                               long shipments, List<BuyerSummary> buyers) {
    public record BuyerSummary(String buyerCode, long orders, long pos, long cartons, long items,
                               long completedCartons, long weightWarnings) {}
}
