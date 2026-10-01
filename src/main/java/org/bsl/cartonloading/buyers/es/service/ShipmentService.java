package org.bsl.cartonloading.buyers.es.service;

import org.bsl.cartonloading.buyers.es.model.CartonScanTransaction;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.buyers.es.enums.CartonLifecycleStatus;
import org.bsl.cartonloading.common.constants.FilterValue;
import org.bsl.cartonloading.buyers.es.enums.InspectionResult;
import org.bsl.cartonloading.buyers.es.enums.ShipmentStatus;
import org.bsl.cartonloading.buyers.es.enums.WeightStatus;

import org.bsl.cartonloading.buyers.es.dto.CartonInspectionRequest;
import org.bsl.cartonloading.buyers.es.support.ShipmentWorkflowPolicy;
import org.bsl.cartonloading.buyers.es.dto.ShipmentRequest;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.buyers.es.model.*;
import org.bsl.cartonloading.buyers.es.enums.CartonScanStatus;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.springframework.data.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ShipmentService {
    private final MongoTemplate mongo;
    private final PackingOrderService orders;
    private final CartonLoadingService loading;
    private final AuditLogService audit;
    private final AppSocketPublisher events;

    public ShipmentService(MongoTemplate mongo, PackingOrderService orders, CartonLoadingService loading,
                           AuditLogService audit, AppSocketPublisher events) {
        this.mongo = mongo; this.orders = orders; this.loading = loading; this.audit = audit; this.events = events;
    }

    public Page<CartonScanTransaction> cartons(String buyer, String orderId, String keyword, String shipmentId,
                                               String lifecycle, int page, int size) {
        Criteria criteria = cartonFilter(buyer, orderId, keyword, shipmentId, lifecycle);
        Pageable paging = PageRequest.of(Math.max(page, 0), Math.max(1, Math.min(size, 200)));
        long total = mongo.count(Query.query(criteria), CartonScanTransaction.class);
        List<CartonScanTransaction> rows = mongo.find(Query.query(criteria).with(paging)
                .with(Sort.by("orderId", "orderCartonSequence", "id")), CartonScanTransaction.class);
        return new PageImpl<>(rows, paging, total);
    }

    public String export(String buyer, String orderId, String keyword, String shipmentId, String lifecycle) {
        Query query = Query.query(cartonFilter(buyer, orderId, keyword, shipmentId, lifecycle));
        if (mongo.count(query, CartonScanTransaction.class) > 50000) {
            throw new IllegalArgumentException("Report exceeds 50,000 cartons. Filter by Order or Shipment first.");
        }
        StringBuilder csv = new StringBuilder("\uFEFFBarcode,Order,PO,Product,Style,Color,Size,Planned quantity,Assigned quantity,Production line,Lifecycle,Weight kg,Weight result,Manual result,Inspection note,Inspected by,Completed by,Shipment,Shipped at\r\n");
        for (CartonScanTransaction row : mongo.find(query.with(Sort.by("orderId", "orderCartonSequence")), CartonScanTransaction.class)) {
            Object[] values = {row.getFactoryBarcode(), row.getOrderName(), row.getPoNumber(), row.getArticleNumber(),
                    row.getStyle(), row.getColor(), row.getSize(), row.getCartonPcs(), row.getAssignedQuantity(),
                    row.getProductionLine(), row.getLifecycleStatus(), row.getWeightKg(), row.getWeightStatus(),
                    row.getManualInspectionResult(), row.getInspectionNote(), row.getInspectedBy(),
                    row.getCompletedBy(), row.getShipmentNo(), row.getShippedAt()};
            csv.append(Arrays.stream(values).map(ShipmentService::csvCell).collect(Collectors.joining(","))).append("\r\n");
        }
        return csv.toString();
    }

    private static String csvCell(Object value) {
        String text = value == null ? "" : value.toString();
        String leading = text.stripLeading();
        if ((!leading.isEmpty() && "=+@-".indexOf(leading.charAt(0)) >= 0) || text.startsWith("\t") || text.startsWith("\r")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private Criteria cartonFilter(String buyer, String orderId, String keyword, String shipmentId, String lifecycle) {
        List<Criteria> filters = new ArrayList<>();
        filters.add(Criteria.where("buyerCode").is(buyer(buyer)));
        if (text(orderId) != null) filters.add(Criteria.where("orderId").is(orderId));
        if (FilterValue.UNPLANNED.equals(shipmentId)) filters.add(Criteria.where("shipmentId").is(null));
        else if (text(shipmentId) != null) filters.add(Criteria.where("shipmentId").is(shipmentId));
        if (text(keyword) != null) {
            Pattern p = Pattern.compile(Pattern.quote(keyword.trim()), Pattern.CASE_INSENSITIVE);
            filters.add(new Criteria().orOperator(Arrays.stream(new String[]{"factoryBarcode", "poNumber", "articleNumber",
                    "style", "styleNumber", "color", "size", "shipmentNo", "cartonCode", "orderName"})
                    .map(field -> Criteria.where(field).regex(p)).toArray(Criteria[]::new)));
        }
        String state = text(lifecycle) == null ? FilterValue.ALL : lifecycle.toUpperCase(Locale.ROOT);
        if (!FilterValue.ALL.equals(state)) {
            if (CartonLifecycleStatus.SHIPPED.name().equals(state)) filters.add(Criteria.where("shippedAt").ne(null));
            else {
                filters.add(Criteria.where("shippedAt").is(null));
                if (CartonLifecycleStatus.CANCELLED.name().equals(state)) filters.add(Criteria.where("status").is(CartonScanStatus.CANCELLED));
                else {
                    filters.add(Criteria.where("status").ne(CartonScanStatus.CANCELLED));
                    if (CartonLifecycleStatus.COMPLETED.name().equals(state)) filters.add(Criteria.where("completedAt").ne(null));
                    else {
                        filters.add(Criteria.where("completedAt").is(null));
                        if (CartonLifecycleStatus.CHECKED.name().equals(state)) filters.add(new Criteria().orOperator(
                                Criteria.where("inspectedAt").ne(null), Criteria.where("weighedAt").ne(null), Criteria.where("weightKg").ne(null)));
                        else if (CartonLifecycleStatus.CREATED.name().equals(state) || CartonLifecycleStatus.ASSIGNED.name().equals(state)) {
                            filters.add(Criteria.where("inspectedAt").is(null));
                            filters.add(Criteria.where("weighedAt").is(null));
                            filters.add(Criteria.where("weightKg").is(null));
                            filters.add(CartonLifecycleStatus.CREATED.name().equals(state)
                                    ? new Criteria().orOperator(Criteria.where("factoryBarcode").is(null), Criteria.where("factoryBarcode").is(""))
                                    : Criteria.where("factoryBarcode").nin(null, ""));
                        } else throw new IllegalArgumentException("Unsupported lifecycle: " + lifecycle);
                    }
                }
            }
        }
        return new Criteria().andOperator(filters.toArray(Criteria[]::new));
    }

    public CartonScanTransaction inspect(String buyer, String cartonId, CartonInspectionRequest request) {
        CartonScanTransaction carton = carton(buyer, cartonId);
        requireOpen(carton);
        if (text(carton.getFactoryBarcode()) == null) throw new IllegalArgumentException("Assign a Factory Barcode first.");
        if (carton.getStatus() == CartonScanStatus.WAITING_WEIGHT) throw new IllegalArgumentException("Finish the pending weight job first.");
        loading.requireCartonShipmentReady(carton);
        String result = required(request == null ? null : request.result(), "Inspection result").toUpperCase(Locale.ROOT);
        if (!List.of(InspectionResult.PASS.name(), InspectionResult.FAIL.name()).contains(result)) throw new IllegalArgumentException("Result must be PASS or FAIL.");
        String note = required(request.note(), "Inspection note");
        if (InspectionResult.PASS.name().equals(result) && List.of(WeightStatus.UNDER.name(), WeightStatus.OVER.name()).contains(Objects.toString(carton.getWeightStatus(), ""))) {
            throw new IllegalArgumentException("Weight failure cannot be overridden. Ask Admin to reset and recheck this carton.");
        }
        LocalDateTime now = LocalDateTime.now();
        Update update = new Update().set("manualInspectionResult", result).set("inspectionNote", note)
                .set("inspectedBy", RequestActor.current()).set("inspectedAt", now).set("updatedAt", now)
                .set("status", InspectionResult.PASS.name().equals(result) ? CartonScanStatus.COMPLETED : CartonScanStatus.WEIGHT_WARNING);
        if (carton.getWeightKg() == null) update.set("weightStatus", WeightStatus.NO_STANDARD.name());
        CartonScanTransaction saved = mongo.findAndModify(Query.query(new Criteria().andOperator(
                openCriteria(buyer, cartonId), Criteria.where("status").is(carton.getStatus()),
                Criteria.where("inspectedAt").is(carton.getInspectedAt()), Criteria.where("weightKg").is(carton.getWeightKg()),
                Criteria.where("factoryBarcode").is(carton.getFactoryBarcode()))), update, returning(), CartonScanTransaction.class);
        if (saved == null) throw changed();
        log("MANUAL_INSPECTION", saved.getId(), result + ": " + note);
        return saved;
    }

    public CartonScanTransaction complete(String buyer, String cartonId) {
        CartonScanTransaction carton = carton(buyer, cartonId);
        if (carton.getCompletedAt() != null) return carton;
        requireOpen(carton);
        if (!CartonLifecycleStatus.CHECKED.name().equals(carton.getLifecycleStatus()) || !carton.isInspectionPassed()
                || carton.getStatus() != CartonScanStatus.COMPLETED || text(carton.getFactoryBarcode()) == null) {
            throw new IllegalArgumentException("Only an assigned carton with a PASS inspection can be completed. NO_STANDARD alone is not a pass.");
        }
        loading.requireCartonShipmentReady(carton);
        CartonScanTransaction saved = mongo.findAndModify(Query.query(new Criteria().andOperator(openCriteria(buyer, cartonId),
                Criteria.where("status").is(CartonScanStatus.COMPLETED), Criteria.where("weightStatus").is(carton.getWeightStatus()),
                Criteria.where("manualInspectionResult").is(carton.getManualInspectionResult()),
                Criteria.where("factoryBarcode").is(carton.getFactoryBarcode()), Criteria.where("inspectedAt").is(carton.getInspectedAt()))),
                new Update().set("completedAt", LocalDateTime.now()).set("completedBy", RequestActor.current())
                        .set("updatedAt", LocalDateTime.now()), returning(), CartonScanTransaction.class);
        if (saved == null) throw changed();
        log("CARTON_COMPLETED", cartonId, "Packing completion confirmed");
        return saved;
    }

    public Shipment create(String buyer, ShipmentRequest request) {
        if (request == null) throw new IllegalArgumentException("Shipment request is required.");
        String code = buyer(buyer);
        orders.getEntity(code, required(request.orderId(), "Order"));
        Shipment shipment = new Shipment();
        shipment.setId(UUID.randomUUID().toString()); shipment.setBuyerCode(code); shipment.setOrderId(request.orderId());
        shipment.setShipmentNo(required(request.shipmentNo(), "Shipment number"));
        shipment.setDestination(required(request.destination(), "Destination"));
        if (request.plannedDate() == null) throw new IllegalArgumentException("Planned shipment date is required.");
        shipment.setPlannedDate(request.plannedDate()); shipment.setCarrier(text(request.carrier())); shipment.setReference(text(request.reference()));
        List<String> ids = request.cartonIds() == null ? List.of() : request.cartonIds().stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > 5000) throw new IllegalArgumentException("Select between 1 and 5,000 physical cartons.");
        shipment.setCartonIds(ids); shipment.setStatus(ShipmentStatus.PREPARING.name()); shipment.setCreatedBy(RequestActor.current()); shipment.setCreatedAt(LocalDateTime.now());
        mongo.insert(shipment);
        try {
            for (String id : ids) {
                CartonScanTransaction candidate = mongo.findOne(Query.query(Criteria.where("_id").is(id)
                        .and("buyerCode").is(code).and("orderId").is(request.orderId())), CartonScanTransaction.class);
                if (candidate == null) throw new IllegalArgumentException("Physical carton not found in the selected Order.");
                java.math.BigDecimal plannedQuantity = candidate.getCartonPcs() == null ? candidate.getQtyPerCarton() : candidate.getCartonPcs();
                ShipmentWorkflowPolicy.requireQuantity(candidate.getAssignedQuantity(), plannedQuantity);

                CartonScanTransaction claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(id)
                        .and("buyerCode").is(code).and("orderId").is(request.orderId()).and("shipmentId").is(null)
                        .and("shippedAt").is(null).and("status").is(CartonScanStatus.PLANNED)
                        .and("factoryBarcode").exists(true).nin(null, "").and("assignedQuantity").gt(java.math.BigDecimal.ZERO)
                        .and("completedAt").is(null).and("weightKg").is(null).and("jobId").is(null)),
                        new Update().set("shipmentId", shipment.getId()).set("shipmentNo", shipment.getShipmentNo()), returning(), CartonScanTransaction.class);
                if (claimed == null) throw new IllegalArgumentException("Select assigned, unweighed cartons with a confirmed quantity from the same Order. A carton may belong to only one shipment plan.");
            }
            shipment.setStatus(ShipmentStatus.PLANNED.name()); mongo.save(shipment);
        } catch (RuntimeException ex) {
            mongo.updateMulti(Query.query(Criteria.where("shipmentId").is(shipment.getId())),
                    new Update().unset("shipmentId").unset("shipmentNo"), CartonScanTransaction.class);
            mongo.remove(Query.query(Criteria.where("_id").is(shipment.getId())), Shipment.class);
            throw ex;
        }
        log("SHIPMENT_PLANNED", shipment.getId(), shipment.getShipmentNo());
        return shipment;
    }

    public Page<Map<String, Object>> list(String buyer, int page, int size) {
        Pageable paging = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 100)));
        Criteria filter = Criteria.where("buyerCode").is(buyer(buyer));
        long total = mongo.count(Query.query(filter), Shipment.class);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Shipment shipment : mongo.find(Query.query(filter).with(paging).with(Sort.by(Sort.Direction.DESC, "createdAt")), Shipment.class)) {
            List<CartonScanTransaction> cartons = shipmentCartons(shipment);
            Map<String, Object> row = new LinkedHashMap<>(); row.put("shipment", shipment);
            row.put("planned", shipment.getCartonIds().size());
            row.put("checked", cartons.stream().filter(c -> c.getInspectedAt() != null || c.getWeighedAt() != null || c.getWeightKg() != null).count());
            row.put("completed", cartons.stream().filter(c -> c.getCompletedAt() != null).count());
            row.put("shipped", cartons.stream().filter(c -> c.getShippedAt() != null).count());
            row.put("failed", cartons.stream().filter(c -> c.getStatus() == CartonScanStatus.WEIGHT_WARNING || InspectionResult.FAIL.name().equals(c.getManualInspectionResult())).count());
            rows.add(row);
        }
        return new PageImpl<>(rows, paging, total);
    }

    public Shipment dispatch(String buyer, String id) {
        Shipment shipment = shipment(buyer, id);
        if (ShipmentStatus.SHIPPED.name().equals(shipment.getStatus())) return shipment;
        if (!List.of(ShipmentStatus.PLANNED.name(), ShipmentStatus.DISPATCHING.name()).contains(shipment.getStatus())) throw new IllegalArgumentException("Shipment is not ready for dispatch.");
        List<CartonScanTransaction> cartons = shipmentCartons(shipment);
        if (cartons.size() != shipment.getCartonIds().size() || cartons.isEmpty()
                || cartons.stream().anyMatch(c -> c.getCompletedAt() == null || !c.isInspectionPassed())) {
            throw new IllegalArgumentException("Every planned carton must pass inspection and be explicitly completed before shipment.");
        }
        // Completed cartons are immutable. CAS claims shipment against a concurrent cancellation.
        if (ShipmentStatus.PLANNED.name().equals(shipment.getStatus())) {
            Shipment claimed = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("buyerCode").is(buyer(buyer)).and("status").is(ShipmentStatus.PLANNED.name())),
                    new Update().set("status", ShipmentStatus.DISPATCHING.name()).set("shippedAt", LocalDateTime.now()).set("shippedBy", RequestActor.current()), returning(), Shipment.class);
            if (claimed == null) throw changed();
            shipment = claimed;
        }
        // Retry resumes DISPATCHING after an interrupted request without dispatching a second time.
        mongo.updateMulti(Query.query(Criteria.where("shipmentId").is(id).and("completedAt").ne(null).and("shippedAt").is(null)),
                new Update().set("shippedAt", shipment.getShippedAt()).set("shippedBy", shipment.getShippedBy()), CartonScanTransaction.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and("status").is(ShipmentStatus.DISPATCHING.name())), new Update().set("status", ShipmentStatus.SHIPPED.name()), Shipment.class);
        log("SHIPMENT_SHIPPED", id, shipment.getShipmentNo());
        return shipment(buyer, id);
    }

    public void cancel(String buyer, String id) {
        Shipment current = shipment(buyer, id);
        ShipmentWorkflowPolicy.requireCancellable(current.getStatus(), current.isExecutionStarted());
        if (!ShipmentStatus.CANCELLED.name().equals(current.getStatus())) {
            Shipment cancelled = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("buyerCode").is(buyer(buyer))
                    .and("status").is(ShipmentStatus.PLANNED.name()).and("executionStarted").ne(true)), new Update().set("status", ShipmentStatus.CANCELLED.name()), returning(), Shipment.class);
            if (cancelled == null) throw changed();
        }
        mongo.updateMulti(Query.query(Criteria.where("shipmentId").is(id).and("shippedAt").is(null)),
                new Update().unset("shipmentId").unset("shipmentNo"), CartonScanTransaction.class);
        log("SHIPMENT_CANCELLED", id, current.getShipmentNo());
    }

    private List<CartonScanTransaction> shipmentCartons(Shipment shipment) {
        return mongo.find(Query.query(Criteria.where("shipmentId").is(shipment.getId()).and("buyerCode").is(shipment.getBuyerCode())
                .and("_id").in(shipment.getCartonIds())), CartonScanTransaction.class);
    }
    public Shipment shipment(String buyer, String id) {
        Shipment value = mongo.findOne(Query.query(Criteria.where("_id").is(id).and("buyerCode").is(buyer(buyer))), Shipment.class);
        if (value == null) throw new IllegalArgumentException("Shipment not found for this Buyer.");
        return value;
    }
    private CartonScanTransaction carton(String buyer, String id) { return loading.get(buyer, id); }
    private Criteria openCriteria(String buyer, String id) {
        return Criteria.where("_id").is(id).and("buyerCode").is(buyer(buyer)).and("completedAt").is(null)
                .and("shippedAt").is(null).and("status").ne(CartonScanStatus.CANCELLED);
    }
    private void requireOpen(CartonScanTransaction carton) {
        if (carton.getCompletedAt() != null || carton.getShippedAt() != null || carton.getStatus() == CartonScanStatus.CANCELLED)
            throw new IllegalArgumentException("Completed, shipped or cancelled cartons cannot be changed.");
    }
    private void log(String action, String id, String detail) {
        audit.log(RequestActor.current(), action, "SHIPMENT_CARTON", id, detail, null, null);
        events.cartonLoadingChanged(action, id);
    }
    private static FindAndModifyOptions returning() { return FindAndModifyOptions.options().returnNew(true); }
    private static IllegalArgumentException changed() { return new IllegalArgumentException("Data changed. Refresh before retrying."); }
    private static String buyer(String value) { return required(BuyerAccess.normalize(value), "Buyer"); }
    private static String text(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value, String field) {
        String clean = text(value); if (clean == null) throw new IllegalArgumentException(field + " is required.");
        if (clean.length() > 1000) throw new IllegalArgumentException(field + " is too long.");
        return clean;
    }
}
