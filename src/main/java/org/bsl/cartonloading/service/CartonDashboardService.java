package org.bsl.cartonloading.service;

import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.buyers.es.model.PackingListLine;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.model.Shipment;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;
import org.bsl.cartonloading.dto.management.DashboardSummary;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.model.User;
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
    public CartonDashboardService(MongoTemplate mongo, UserRepository users) { this.mongo = mongo; this.users = users; }

    public DashboardSummary summary() {
        User user = currentUser();
        List<String> buyers = user.isAdminRole() ? BuyerAccess.ALL : user.getBuyerPermissions();
        List<DashboardSummary.BuyerSummary> rows = new ArrayList<>();
        long orders=0,pos=0,cartons=0,items=0,assigned=0,completed=0,warnings=0,shipments=0;
        for (String buyer : buyers) {
            DashboardSummary.BuyerSummary row = buyerSummary(buyer);
            rows.add(row); orders += row.orders(); pos += row.pos(); cartons += row.cartons(); items += row.items();
            completed += row.completedCartons(); warnings += row.weightWarnings();
            if (BuyerAccess.LULULEMON.equals(buyer)) {
                assigned += mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), LululemonCarton.class).stream()
                        .filter(this::scoped)
                        .filter(carton -> carton.getSscc18() != null && !carton.getSscc18().isBlank())
                        .count();
            } else {
                assigned += mongo.count(Query.query(Criteria.where("buyerCode").is(buyer).and("factoryBarcode").nin(null, "")), CartonScanTransaction.class);
                shipments += mongo.count(Query.query(Criteria.where("buyerCode").is(buyer)), Shipment.class);
            }
        }
        return new DashboardSummary(orders,pos,cartons,items,assigned,completed,warnings,shipments,rows);
    }

    private DashboardSummary.BuyerSummary buyerSummary(String buyer) {
        long orders = mongo.count(Query.query(Criteria.where("buyerCode").is(buyer)), PackingOrder.class);
        if (BuyerAccess.LULULEMON.equals(buyer)) {
            List<LululemonPo> poRows = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), LululemonPo.class).stream()
                    .filter(this::scoped)
                    .toList();
            List<LululemonCarton> cartonRows = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), LululemonCarton.class).stream()
                    .filter(this::scoped)
                    .toList();
            long plannedCartons = poRows.stream().map(LululemonPo::getPlannedCartons).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            long plannedItems = poRows.stream().map(LululemonPo::getPlannedTotalQty).filter(Objects::nonNull).mapToLong(Integer::longValue).sum();
            long completed = cartonRows.stream().filter(c -> Set.of("FINISHED", "LABEL_CONFIRMED", "READY_TO_SHIP").contains(c.getStatus())).count();
            return new DashboardSummary.BuyerSummary(buyer, orders, poRows.size(), plannedCartons, plannedItems, completed, 0);
        }
        List<PackingListLine> lines = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), PackingListLine.class);
        long pos = lines.stream().map(PackingListLine::getPoNumber).filter(Objects::nonNull).filter(v -> !v.isBlank()).distinct().count();
        List<CartonScanTransaction> physical = mongo.find(Query.query(Criteria.where("buyerCode").is(buyer)), CartonScanTransaction.class);
        long cartons = physical.stream().filter(c -> c.getStatus() != CartonScanStatus.CANCELLED).count();
        long items = physical.stream().filter(c -> c.getStatus() != CartonScanStatus.CANCELLED).mapToLong(c -> quantity(c).longValue()).sum();
        long completed = physical.stream().filter(c -> c.getCompletedAt() != null || c.getShippedAt() != null).count();
        long warnings = physical.stream().filter(c -> c.getStatus() == CartonScanStatus.WEIGHT_WARNING || "OVER".equalsIgnoreCase(c.getWeightStatus()) || "UNDER".equalsIgnoreCase(c.getWeightStatus())).count();
        return new DashboardSummary.BuyerSummary(buyer, orders, pos, cartons, items, completed, warnings);
    }

    private boolean scoped(LululemonPo value) { return value.getOrderId() != null && !value.getOrderId().isBlank(); }
    private boolean scoped(LululemonCarton value) { return value.getOrderId() != null && !value.getOrderId().isBlank(); }

    private BigDecimal quantity(CartonScanTransaction c) {
        if (c.getAssignedQuantity() != null) return c.getAssignedQuantity();
        if (c.getCartonPcs() != null) return c.getCartonPcs();
        if (c.getQtyPerCarton() != null) return c.getQtyPerCarton();
        return BigDecimal.ZERO;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) throw new IllegalArgumentException("Authentication is required");
        return users.findByEmail(auth.getName()).filter(User::isEnabled).orElseThrow(() -> new IllegalArgumentException("Current User is not available"));
    }
}
