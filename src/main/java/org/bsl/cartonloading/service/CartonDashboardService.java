package org.bsl.cartonloading.service;

import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.dto.management.DashboardSummary;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
import org.bsl.cartonloading.repository.BuyerRepository;
import org.bsl.cartonloading.repository.UserRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class CartonDashboardService {
    private final MongoTemplate mongo;
    private final UserRepository users;
    private final BuyerRepository buyers;
    public CartonDashboardService(MongoTemplate mongo, UserRepository users, BuyerRepository buyers) {
        this.mongo = mongo;
        this.users = users;
        this.buyers = buyers;
    }

    public DashboardSummary summary() {
        User user = currentUser();
        List<String> buyerCodes = user.isAdminRole()
                ? buyers.findByActiveTrueOrderBySequenceAscBuyerNameAsc().stream().map(b -> b.getBuyerKey()).toList()
                : user.getBuyerPermissions();
        List<DashboardSummary.BuyerSummary> rows = new ArrayList<>();
        long orders=0,pos=0,cartons=0,items=0,assigned=0,completed=0,warnings=0,shipments=0;
        for (String buyer : buyerCodes) {
            DashboardSummary.BuyerSummary row = buyerSummary(buyer);
            rows.add(row); orders += row.orders(); pos += row.pos(); cartons += row.cartons(); items += row.items();
            completed += row.completedCartons(); warnings += row.weightWarnings();
            if (AppConstants.Buyer.LULULEMON.equals(buyer)) {
                assigned += mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), Carton.class).stream()
                        .filter(this::scoped)
                        .filter(carton -> carton.getSscc18() != null && !carton.getSscc18().isBlank())
                        .count();
            } else if (AppConstants.Buyer.ENGELBERT_STRAUSS.equals(buyer)) {
                assigned += mongo.count(Query.query(Criteria.where("buyerCode").is(buyer).and("factoryBarcode").nin(null, "")), CartonScanTransaction.class);
                shipments += mongo.count(Query.query(Criteria.where("buyerCode").is(buyer)), Shipment.class);
            }
        }
        return new DashboardSummary(orders,pos,cartons,items,assigned,completed,warnings,shipments,rows);
    }

    private DashboardSummary.BuyerSummary buyerSummary(String buyer) {
        long orders = mongo.count(Query.query(Criteria.where("buyerCode").is(buyer)), PackingOrder.class);
        if (AppConstants.Buyer.LULULEMON.equals(buyer)) {
            List<PurchaseOrder> poRows = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), PurchaseOrder.class).stream()
                    .filter(this::scoped)
                    .toList();
            List<Carton> cartonRows = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), Carton.class).stream()
                    .filter(this::scoped)
                    .toList();
            long plannedCartons = poRows.stream().map(PurchaseOrder::getPlannedCartons).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            long plannedItems = poRows.stream().map(PurchaseOrder::getPlannedTotalQty).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            long completed = cartonRows.stream().filter(c -> Set.of(AppConstants.CartonStatus.FINISHED, AppConstants.CartonStatus.LABEL_CONFIRMED, AppConstants.CartonStatus.READY_TO_SHIP).contains(c.getStatus())).count();
            return new DashboardSummary.BuyerSummary(buyer, orders, poRows.size(), plannedCartons, plannedItems, completed, 0);
        }
        if (AppConstants.Buyer.ENGELBERT_STRAUSS.equals(buyer)) {
            List<PackingListLine> lines = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), PackingListLine.class);
            long pos = lines.stream().map(PackingListLine::getPoNumber).filter(Objects::nonNull).filter(v -> !v.isBlank()).distinct().count();
            List<CartonScanTransaction> physical = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), CartonScanTransaction.class);
            long cartons = physical.stream().filter(c -> c.getStatus() != CartonScanStatus.CANCELLED).count();
            long items = physical.stream().filter(c -> c.getStatus() != CartonScanStatus.CANCELLED).mapToLong(c -> quantity(c).longValue()).sum();
            long completed = physical.stream().filter(c -> c.getCompletedAt() != null || c.getShippedAt() != null).count();
            long warnings = physical.stream().filter(c -> c.getStatus() == CartonScanStatus.WEIGHT_WARNING || AppConstants.ScaleWeightStatus.OVER.equalsIgnoreCase(c.getWeightStatus()) || AppConstants.ScaleWeightStatus.UNDER.equalsIgnoreCase(c.getWeightStatus())).count();
            return new DashboardSummary.BuyerSummary(buyer, orders, pos, cartons, items, completed, warnings);
        }

        // A newly configured Buyer can exist before a dedicated workflow module is added.
        // Do not silently interpret it as the ES workflow.
        return new DashboardSummary.BuyerSummary(buyer, orders, 0, 0, 0, 0, 0);
    }

    private boolean scoped(PurchaseOrder value) { return value.getOrderId() != null && !value.getOrderId().isBlank(); }
    private boolean scoped(Carton value) { return value.getOrderId() != null && !value.getOrderId().isBlank(); }

    private BigDecimal quantity(CartonScanTransaction c) {
        if (c.getAssignedQuantity() != null) return c.getAssignedQuantity();
        if (c.getCartonPcs() != null) return c.getCartonPcs();
        if (c.getQtyPerCarton() != null) return c.getQtyPerCarton();
        return BigDecimal.ZERO;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) throw new IllegalArgumentException(AppMessages.AUTHENTICATION_REQUIRED);
        return users.findByEmail(auth.getName()).filter(User::isEnabled).orElseThrow(() -> new IllegalArgumentException(AppMessages.CURRENT_USER_UNAVAILABLE));
    }
}
