package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.FactoryCatalogService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class ShippingScheduleService {
    public static final String PLANNED = "PLANNED";
    public static final String REVIEWED = "REVIEWED";
    public static final String SENT_TO_WEIGHT = "SENT_TO_WEIGHT";
    public static final String WEIGHING = "WEIGHING";
    public static final String COMPLETED = "COMPLETED";

    private final ShippingScheduleRepository scheduleRepository;
    private final PurchaseOrderRepository poRepository;
    private final CartonRepository cartonRepository;
    private final WeighingResultRepository weightResultRepository;
    private final PackingOrderService orderService;
    private final BuyerFactoryAccessService factoryAccess;
    private final WeighingService weighingService;
    private final AuditLogService auditLogService;
    private final FactoryCatalogService factories;

    public ShippingScheduleService(
            ShippingScheduleRepository scheduleRepository,
            PurchaseOrderRepository poRepository,
            CartonRepository cartonRepository,
            WeighingResultRepository weightResultRepository,
            PackingOrderService orderService,
            BuyerFactoryAccessService factoryAccess,
            WeighingService weighingService,
            AuditLogService auditLogService,
            FactoryCatalogService factories
    ) {
        this.scheduleRepository = scheduleRepository;
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.weightResultRepository = weightResultRepository;
        this.orderService = orderService;
        this.factoryAccess = factoryAccess;
        this.weighingService = weighingService;
        this.auditLogService = auditLogService;
        this.factories = factories;
    }

    public List<ShippingScheduleSummary> list(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        return scheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream()
                // Sales/Admin see every schedule. Operational users only see schedules
                // explicitly assigned to one of their Factory permissions (F1/F2/...).
                .filter(schedule -> factoryAccess.canAccessAssignedFactory(schedule.getFactoryCode())
                        || factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode()))
                .map(this::summary).toList();
    }

    public ShippingScheduleDetail detail(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        if (!factoryAccess.canAccessAssignedFactory(schedule.getFactoryCode())
                && !factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied");
        }
        return buildDetail(schedule);
    }

    public synchronized ShippingScheduleDetail create(
            String buyerCode, String orderId, ShippingScheduleCreateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        String factory = normalizeFactory(request == null ? null : request.factoryCode());
        if (factory == null) throw new IllegalArgumentException("Select a Factory for this Shipping Schedule.");
        // Packing may only dispatch shipments for explicitly assigned factories.
        // Validate on the server; filtering the Factory dropdown is not authorization.
        factories.requireActive(AppConstants.Buyer.LULULEMON, factory); // never create in INACTIVE factory
        factoryAccess.assertAssignedFactoryAccess(factory); // enforces user assignment
        if (request == null || request.exFtyDate() == null) throw new IllegalArgumentException("Ex-Factory Date is required.");

        String shippingName = request.shippingName() == null ? "" : request.shippingName().trim();
        if (shippingName.isEmpty() || shippingName.length() > 80) {
            throw new IllegalArgumentException("Shipment Name is required (max 80 characters).");
        }
        LinkedHashSet<String> poIds = new LinkedHashSet<>(request.poIds() == null ? List.of() : request.poIds());
        poIds.removeIf(id -> clean(id) == null);
        if (poIds.isEmpty()) throw new IllegalArgumentException("Select at least one logical PO for the Shipping Schedule.");

        List<ShippingSchedule> openSchedules = scheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream().filter(s -> !COMPLETED.equals(s.getStatus())).toList();

        LocalDateTime now = LocalDateTime.now();
        String actor = RequestActor.current();
        List<PurchaseOrder> pos = new ArrayList<>();
        Map<String, ShippingSchedule> existingAssignments = new LinkedHashMap<>();
        for (ShippingSchedule open : openSchedules) {
            if (open.getPoIds() == null) continue;
            for (String existingPoId : open.getPoIds()) {
                existingAssignments.putIfAbsent(existingPoId, open);
            }
        }

        // Validate *every* requested PO before the first database mutation.
        // In particular, rejecting a cross-Factory Packing submission must not
        // leave earlier carton/PO assignments partly rewritten.
        for (String poId : poIds) {
            PurchaseOrder candidate = requirePo(orderId, poId);
            ShippingSchedule existing = existingAssignments.get(poId);
            if (clean(candidate.getPickedBy()) == null
                    || !factory.equals(normalizeFactory(candidate.getFactoryCode()))) {
                throw new IllegalArgumentException("Pick PO " + logicalPoLabel(candidate)
                        + " for Factory " + factory + " before creating the Shipment.");
            }
            if (!factoryAccess.isUnrestricted()
                    && !factory.equals(normalizeFactory(candidate.getFactoryCode()))) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Packing cannot create shipments with a PO outside Factory " + factory + ": " + logicalPoLabel(candidate));
            }
            if (existing != null) {
                boolean reassignable = (PLANNED.equals(existing.getStatus()) || REVIEWED.equals(existing.getStatus()))
                        && existing.getWeighingOrderId() == null && existing.getSentToWeightAt() == null;
                if (!reassignable) {
                    throw new IllegalArgumentException("PO " + logicalPoLabel(candidate) + " is already locked in Shipping Schedule "
                            + existing.getScheduleNo() + " (" + existing.getStatus() + ").");
                }
                if (!factoryAccess.isUnrestricted()
                        && !factory.equals(normalizeFactory(existing.getFactoryCode()))) {
                    throw new org.springframework.security.access.AccessDeniedException(
                            "Packing cannot move PO " + logicalPoLabel(candidate) + " out of its current Factory shipment.");
                }
            }
        }

        Map<String, List<String>> poIdsMovedBySchedule = new LinkedHashMap<>();
        for (String poId : poIds) {
            PurchaseOrder po = requirePo(orderId, poId);
            // Sales can plan/move PO assignments; Packing must never silently
            // reassign a PO (and its existing cartons) to a different factory.
            if (!factoryAccess.isUnrestricted()
                    && !factory.equals(normalizeFactory(po.getFactoryCode()))) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Packing cannot create shipments with a PO outside Factory " + factory + ": " + logicalPoLabel(po));
            }
            ShippingSchedule current = existingAssignments.get(poId);
            if (current != null && !factoryAccess.isUnrestricted()
                    && !factory.equals(normalizeFactory(current.getFactoryCode()))) {
                throw new org.springframework.security.access.AccessDeniedException(
                        "Packing cannot move PO " + logicalPoLabel(po) + " out of its current Factory shipment.");
            }
            if (current != null) {
                boolean reassignable = (PLANNED.equals(current.getStatus()) || REVIEWED.equals(current.getStatus()))
                        && current.getWeighingOrderId() == null
                        && current.getSentToWeightAt() == null;
                if (!reassignable) {
                    throw new IllegalArgumentException("PO " + logicalPoLabel(po) + " is already locked in Shipping Schedule " + current.getScheduleNo() + " (" + current.getStatus() + ").");
                }
                poIdsMovedBySchedule.computeIfAbsent(current.getId(), ignored -> new ArrayList<>()).add(poId);
            }

            po.setFactoryCode(factory);
            po.setExFtyDate(request.exFtyDate());
            po.setUpdatedBy(actor);
            po.setUpdatedAt(now);
            pos.add(po);
            // Cartons may already have been lazily generated before Sales creates the schedule.
            // Keep their Factory aligned with the logical PO selected for this shipping schedule.
            List<Carton> existingCartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
            if (!existingCartons.isEmpty()) {
                existingCartons.forEach(carton -> {
                    carton.setFactoryCode(factory);
                    carton.setUpdatedBy(actor);
                    carton.setUpdatedAt(now);
                });
                cartonRepository.saveAll(existingCartons);
            }
        }
        poRepository.saveAll(pos);

        for (Map.Entry<String, List<String>> entry : poIdsMovedBySchedule.entrySet()) {
            ShippingSchedule previous = existingAssignments.values().stream()
                    .filter(schedule -> Objects.equals(schedule.getId(), entry.getKey()))
                    .findFirst()
                    .orElse(null);
            if (previous == null) continue;

            List<String> remainingPoIds = new ArrayList<>(previous.getPoIds() == null ? List.of() : previous.getPoIds());
            remainingPoIds.removeIf(entry.getValue()::contains);
            addHistory(previous, "REASSIGNED", "Sales reassigned " + entry.getValue().size() + " logical PO(s) to a new Shipping Schedule.");
            previous.setUpdatedBy(actor);
            previous.setUpdatedAt(now);
            if (remainingPoIds.isEmpty()) {
                audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_REMOVE, previous.getId(),
                        "Removed empty schedule " + previous.getScheduleNo() + " after reassigning all logical POs.");
                scheduleRepository.delete(previous);
            } else {
                previous.setPoIds(remainingPoIds);
                scheduleRepository.save(previous);
            }
        }

        ShippingSchedule schedule = new ShippingSchedule();
        schedule.setBuyerCode(AppConstants.Buyer.LULULEMON);
        schedule.setOrderId(orderId);
        schedule.setScheduleNo("SHIP-" + request.exFtyDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + now.format(DateTimeFormatter.ofPattern("HHmmss")));
        schedule.setShippingName(shippingName);
        schedule.setFactoryCode(factory);
        schedule.setExFtyDate(request.exFtyDate());
        schedule.setPoIds(new ArrayList<>(poIds));
        schedule.setStatus(PLANNED);
        schedule.setCreatedBy(actor);
        schedule.setCreatedAt(now);
        schedule.setUpdatedBy(actor);
        schedule.setUpdatedAt(now);
        addHistory(schedule, "CREATED", "Packing created Shipment from " + poIds.size() + " picked logical PO(s) for Factory " + factory + " on " + request.exFtyDate() + ".");
        schedule = scheduleRepository.save(schedule);
        audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_CREATE, schedule.getId(),
                "Created " + schedule.getScheduleNo() + " factory=" + factory + " exFty=" + request.exFtyDate() + " POs=" + poIds.size());
        return buildDetail(schedule);
    }

    /**
     * Sales may remove a Shipping Schedule while Packing has not yet confirmed
     * transfer to the Carton Weight area. PLANNED and REVIEWED are both removable;
     * scan/Carton/SKU/SSCC data are intentionally untouched.
     */
    public synchronized void remove(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        assertScheduleFactory(schedule);

        boolean removableStatus = PLANNED.equals(schedule.getStatus()) || REVIEWED.equals(schedule.getStatus());
        boolean alreadyConfirmedToWeight = schedule.getWeighingOrderId() != null || schedule.getSentToWeightAt() != null;
        if (!removableStatus || alreadyConfirmedToWeight) {
            throw new IllegalArgumentException(AppMessages.SHIPPING_SCHEDULE_REMOVE_BLOCKED);
        }

        String scheduleNo = schedule.getScheduleNo();
        audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_REMOVE, schedule.getId(),
                "Removed " + scheduleNo + " before Packing confirmed transfer to Carton Weight. Packing scan, Carton, SKU and SSCC data were retained.");
        scheduleRepository.delete(schedule);
    }

    /** Packing uses CHECK to refresh the schedule and persist who reviewed it. */
    public synchronized ShippingScheduleDetail review(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        assertScheduleFactory(schedule);
        if (!PLANNED.equals(schedule.getStatus()) && !REVIEWED.equals(schedule.getStatus())) {
            throw new IllegalArgumentException("Shipment has already been sent to Weight; it cannot be CHECKED again.");
        }
        LocalDateTime now = LocalDateTime.now();
        schedule.setReviewedBy(RequestActor.current());
        schedule.setReviewedAt(now);
        if (PLANNED.equals(schedule.getStatus())) schedule.setStatus(REVIEWED);
        schedule.setUpdatedBy(RequestActor.current());
        schedule.setUpdatedAt(now);
        addHistory(schedule, "PACKING_CHECK", "Packing reviewed SKU scan and SSCC readiness.");
        schedule = scheduleRepository.save(schedule);
        audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_CHECK, schedule.getId(), "Packing checked " + schedule.getScheduleNo());
        return buildDetail(schedule);
    }

    /**
     * Packing sends the complete schedule to Carton Weight only after every logical PO
     * has an assigned SKU and every generated carton has an SSCC.
     */
    public synchronized ShippingScheduleDetail sendToCartonWeight(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        assertScheduleFactory(schedule);
        if (COMPLETED.equals(schedule.getStatus())) throw new IllegalArgumentException("Shipping Schedule is already completed.");
        if (schedule.getWeighingOrderId() != null) return buildDetail(schedule);
        // A single SEND click performs the readiness check. No separate CHECK step is required.
        // Weight assignments must never be created until Packing explicitly sends the shipment.
        if (!PLANNED.equals(schedule.getStatus()) && !REVIEWED.equals(schedule.getStatus())) {
            throw new IllegalArgumentException("Shipment is not in a sendable state: " + schedule.getStatus());
        }

        ShippingScheduleDetail detail = buildDetail(schedule);
        if (!detail.readyToSendToWeight()) {
            List<String> notReady = detail.pos().stream()
                    .filter(row -> !row.readyForWeight())
                    .map(row -> logicalPoLabel(row.po())
                            + " [SKU=" + (row.skuAssigned() ? "OK" : "MISSING")
                            + ", IDENTIFIED=" + row.finishedCartons() + "/" + row.totalCartons()
                            + ", SSCC=" + row.ssccAssignedCartons() + "/" + row.totalCartons() + "]")
                    .toList();
            throw new IllegalArgumentException("Shipping Schedule is not ready for Carton Weight. Check: " + String.join("; ", notReady));
        }

        WeighingOrder weighingOrder = weighingService.createWeighingOrder(
                buyerCode,
                orderId,
                new WeighingOrderCreateRequest(schedule.getScheduleNo(), new ArrayList<>(schedule.getPoIds()), schedule.getId())
        );
        LocalDateTime now = LocalDateTime.now();
        // Preserve who approved the readiness check in the same atomic SEND workflow.
        if (schedule.getReviewedAt() == null) {
            schedule.setReviewedBy(RequestActor.current());
            schedule.setReviewedAt(now);
            addHistory(schedule, "PACKING_CHECK_AUTO", "Carton identities automatically validated during SEND TO WEIGHT.");
        }
        schedule.setWeighingOrderId(weighingOrder.getId());
        schedule.setStatus(SENT_TO_WEIGHT);
        schedule.setSentToWeightBy(RequestActor.current());
        schedule.setSentToWeightAt(now);
        schedule.setUpdatedBy(RequestActor.current());
        schedule.setUpdatedAt(now);
        addHistory(schedule, "SENT_TO_CARTON_WEIGHT", "Packing sent the schedule to Carton Weight.");
        schedule = scheduleRepository.save(schedule);
        audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_SEND_CW, schedule.getId(),
                "Sent " + schedule.getScheduleNo() + " to Carton Weight, weighingOrder=" + weighingOrder.getId());
        return buildDetail(schedule);
    }

    public List<WeighingResult> weightHistory(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        if (!factoryAccess.canAccessAssignedFactory(schedule.getFactoryCode())
                && !factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied");
        }
        return weightResultRepository.findByShippingScheduleIdOrderByCreatedAtDesc(schedule.getId());
    }

    public ShippingScheduleSummary summary(ShippingSchedule schedule) {
        ShippingScheduleDetail detail = buildDetail(schedule);
        return new ShippingScheduleSummary(
                detail.schedule(), detail.totalPos(), detail.readyPos(), detail.totalCartons(),
                detail.ssccAssignedCartons(), detail.passCartons(), detail.failedCartons(),
                detail.remainingWeightCartons(), detail.readyToSendToWeight()
        );
    }

    public ShippingScheduleDetail buildDetail(ShippingSchedule schedule) {
        List<ShippingSchedulePoRow> rows = new ArrayList<>();
        int totalCartons = 0;
        int ssccCartons = 0;
        int passCartons = 0;
        int failedCartons = 0;
        int remainingWeightCartons = 0;
        int readyPos = 0;

        for (String poId : schedule.getPoIds() == null ? List.<String>of() : schedule.getPoIds()) {
            Optional<PurchaseOrder> optional = poRepository.findByIdAndOrderIdAndBuyerCode(poId, schedule.getOrderId(), AppConstants.Buyer.LULULEMON);
            if (optional.isEmpty()) continue;
            PurchaseOrder po = optional.get();
            List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
            // Legacy DTO field is named finishedCartons; its new station-handoff
            // meaning is IDENTITY COMPLETE. Physical contents are verified later by RFID.
            int finished = (int) cartons.stream().filter(c -> CartonWeightReadiness.isReady(po, c)).count();
            int assigned = (int) cartons.stream().filter(c -> clean(c.getSscc()) != null).count();
            int pass = (int) cartons.stream().filter(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())).count();
            int failed = (int) cartons.stream().filter(c -> AppConstants.WeightStatus.FAILED.equals(c.getWeightStatus()) || "HOLD".equals(c.getWeightStatus())).count();
            int remaining = Math.max(0, cartons.size() - pass - failed);
            boolean skuAssigned = clean(po.getSku()) != null;
            boolean ready = skuAssigned && !cartons.isEmpty() && finished == cartons.size() && assigned == cartons.size();
            if (ready) readyPos++;
            totalCartons += cartons.size();
            ssccCartons += assigned;
            passCartons += pass;
            failedCartons += failed;
            remainingWeightCartons += remaining;
            rows.add(new ShippingSchedulePoRow(po, cartons.size(), finished, assigned, pass, failed, remaining, skuAssigned, ready));
        }

        boolean ready = !rows.isEmpty() && readyPos == rows.size();
        return new ShippingScheduleDetail(schedule, rows, rows.size(), readyPos, totalCartons, ssccCartons,
                passCartons, failedCartons, remainingWeightCartons, ready);
    }

    public synchronized void markWeighingStarted(String orderId, String scheduleId) {
        if (clean(scheduleId) == null) return;
        scheduleRepository.findByIdAndOrderIdAndBuyerCode(scheduleId, orderId, AppConstants.Buyer.LULULEMON).ifPresent(schedule -> {
            if (SENT_TO_WEIGHT.equals(schedule.getStatus())) {
                schedule.setStatus(WEIGHING);
                schedule.setUpdatedBy(RequestActor.current());
                schedule.setUpdatedAt(LocalDateTime.now());
                addHistory(schedule, "WEIGHING_STARTED", "Carton Weight started weighing a PO in this schedule.");
                scheduleRepository.save(schedule);
            }
        });
    }

    /** Called by Carton Weight after a PO is completed. Completes the schedule only when every carton is PASS. */
    public synchronized void refreshCompletion(String orderId, String scheduleId) {
        if (clean(scheduleId) == null) return;
        ShippingSchedule schedule = scheduleRepository.findByIdAndOrderIdAndBuyerCode(scheduleId, orderId, AppConstants.Buyer.LULULEMON).orElse(null);
        if (schedule == null || COMPLETED.equals(schedule.getStatus())) return;
        ShippingScheduleDetail detail = buildDetail(schedule);
        List<Carton> allCartons = (schedule.getPoIds() == null ? List.<String>of() : schedule.getPoIds()).stream()
                .flatMap(id -> cartonRepository.findByPoIdOrderByCartonNoAsc(id).stream()).toList();
        String rfidReview = RfidReviewStatus.fromCartons(allCartons);
        schedule.setRfidReviewStatus(rfidReview);
        // New weight-first workflow: shipment FINAL requires RFID PASS, but weight session COMPLETE remains independent.
        boolean allRfidPass = RfidReviewStatus.PASS.equals(rfidReview);
        if (allRfidPass && detail.totalCartons() > 0 && detail.remainingWeightCartons() == 0 && detail.failedCartons() == 0 && detail.passCartons() == detail.totalCartons()) {
            LocalDateTime now = LocalDateTime.now();
            schedule.setStatus(COMPLETED);
            schedule.setCompletedBy(RequestActor.current());
            schedule.setCompletedAt(now);
            schedule.setUpdatedBy(RequestActor.current());
            schedule.setUpdatedAt(now);
            addHistory(schedule, "COMPLETED", "All cartons passed Carton Weight. Shipping history retained with SSCC and weighing results.");
            scheduleRepository.save(schedule);
            audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_COMPLETE, schedule.getId(), "Completed " + schedule.getScheduleNo()
                    + "; RFID review=" + rfidReview);
        } else {
            scheduleRepository.save(schedule);
        }
    }

    private void assertScheduleFactory(ShippingSchedule schedule) {
        // Do not rely on frontend filtering: direct scheduleId access is Factory scoped too.
        factoryAccess.assertAssignedFactoryAccess(schedule.getFactoryCode());
    }

    private ShippingSchedule requireSchedule(String orderId, String scheduleId) {
        return scheduleRepository.findByIdAndOrderIdAndBuyerCode(scheduleId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("Shipping Schedule not found."));
    }

    private PurchaseOrder requirePo(String orderId, String poId) {
        return poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("LULULEMON logical PO not found: " + poId));
    }

    private void requireOrder(String buyerCode, String orderId) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) throw new IllegalArgumentException("This workflow is available only for LULULEMON.");
        orderService.getEntity(AppConstants.Buyer.LULULEMON, orderId);
    }

    private void addHistory(ShippingSchedule schedule, String action, String note) {
        if (schedule.getHistory() == null) schedule.setHistory(new ArrayList<>());
        schedule.getHistory().add(new ShippingScheduleHistoryEntry(action, RequestActor.current(), note, LocalDateTime.now()));
    }

    private void audit(String action, String resourceId, String description) {
        auditLogService.log(RequestActor.current(), action, AppConstants.AuditResource.SHIPPING_SCHEDULE, resourceId, description, null, null);
    }

    private String logicalPoLabel(PurchaseOrder po) {
        return String.join(" / ", List.of(
                value(po.getPoNumber()), value(po.getDcCode()), value(po.getDestination()), value(po.getChannel()),
                value(po.getMasterPo()), value(po.getPackingPlan()), value(po.getSalesOrderPts()), value(po.getStyle()), value(po.getColor())
        ));
    }

    private String value(String value) { return clean(value) == null ? "-" : clean(value); }
    private String normalizeFactory(String value) { String v = clean(value); return v == null ? null : v.toUpperCase(Locale.ROOT); }
    private static String clean(String value) { return TextNormalizer.trimToNull(value); }
}
