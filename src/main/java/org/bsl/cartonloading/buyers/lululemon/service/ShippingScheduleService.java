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

    public ShippingScheduleService(
            ShippingScheduleRepository scheduleRepository,
            PurchaseOrderRepository poRepository,
            CartonRepository cartonRepository,
            WeighingResultRepository weightResultRepository,
            PackingOrderService orderService,
            BuyerFactoryAccessService factoryAccess,
            WeighingService weighingService,
            AuditLogService auditLogService
    ) {
        this.scheduleRepository = scheduleRepository;
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.weightResultRepository = weightResultRepository;
        this.orderService = orderService;
        this.factoryAccess = factoryAccess;
        this.weighingService = weighingService;
        this.auditLogService = auditLogService;
    }

    public List<ShippingScheduleSummary> list(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        return scheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream()
                // Sales/Admin see every schedule. Operational users only see schedules
                // explicitly assigned to one of their Factory permissions (F1/F2/...).
                .filter(schedule -> factoryAccess.canAccessAssignedFactory(schedule.getFactoryCode()))
                .map(this::summary).toList();
    }

    public ShippingScheduleDetail detail(String buyerCode, String orderId, String scheduleId) {
        requireOrder(buyerCode, orderId);
        ShippingSchedule schedule = requireSchedule(orderId, scheduleId);
        assertScheduleFactory(schedule);
        return buildDetail(schedule);
    }

    public synchronized ShippingScheduleDetail create(
            String buyerCode, String orderId, ShippingScheduleCreateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        String factory = normalizeFactory(request == null ? null : request.factoryCode());
        if (factory == null) throw new IllegalArgumentException("Select a Factory for this Shipping Schedule.");
        if (request == null || request.exFtyDate() == null) throw new IllegalArgumentException("Ex-Factory Date is required.");

        LinkedHashSet<String> poIds = new LinkedHashSet<>(request.poIds() == null ? List.of() : request.poIds());
        poIds.removeIf(id -> clean(id) == null);
        if (poIds.isEmpty()) throw new IllegalArgumentException("Select at least one logical PO for the Shipping Schedule.");

        List<ShippingSchedule> openSchedules = scheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream().filter(s -> !COMPLETED.equals(s.getStatus())).toList();

        LocalDateTime now = LocalDateTime.now();
        List<PurchaseOrder> pos = new ArrayList<>();
        for (String poId : poIds) {
            PurchaseOrder po = requirePo(orderId, poId);
            for (ShippingSchedule open : openSchedules) {
                if (open.getPoIds() != null && open.getPoIds().contains(poId)) {
                    throw new IllegalArgumentException("PO " + logicalPoLabel(po) + " is already assigned to Shipping Schedule " + open.getScheduleNo() + ".");
                }
            }
            po.setFactoryCode(factory);
            po.setExFtyDate(request.exFtyDate());
            po.setUpdatedBy(RequestActor.current());
            po.setUpdatedAt(now);
            pos.add(po);
            // Cartons may already have been lazily generated before Sales creates the schedule.
            // Keep their Factory aligned with the logical PO selected for this shipping schedule.
            List<Carton> existingCartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
            if (!existingCartons.isEmpty()) {
                existingCartons.forEach(carton -> {
                    carton.setFactoryCode(factory);
                    carton.setUpdatedBy(RequestActor.current());
                    carton.setUpdatedAt(now);
                });
                cartonRepository.saveAll(existingCartons);
            }
        }
        poRepository.saveAll(pos);

        ShippingSchedule schedule = new ShippingSchedule();
        schedule.setBuyerCode(AppConstants.Buyer.LULULEMON);
        schedule.setOrderId(orderId);
        schedule.setScheduleNo("SHIP-" + request.exFtyDate().format(DateTimeFormatter.BASIC_ISO_DATE)
                + "-" + now.format(DateTimeFormatter.ofPattern("HHmmss")));
        schedule.setFactoryCode(factory);
        schedule.setExFtyDate(request.exFtyDate());
        schedule.setPoIds(new ArrayList<>(poIds));
        schedule.setStatus(PLANNED);
        schedule.setCreatedBy(RequestActor.current());
        schedule.setCreatedAt(now);
        schedule.setUpdatedBy(RequestActor.current());
        schedule.setUpdatedAt(now);
        addHistory(schedule, "CREATED", "Sales assigned " + poIds.size() + " logical PO(s) to Factory " + factory + " for " + request.exFtyDate() + ".");
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
        if (!REVIEWED.equals(schedule.getStatus())) {
            throw new IllegalArgumentException(org.bsl.cartonloading.common.constants.AppMessages.SHIPPING_SCHEDULE_REVIEW_REQUIRED);
        }

        ShippingScheduleDetail detail = buildDetail(schedule);
        if (!detail.readyToSendToWeight()) {
            List<String> notReady = detail.pos().stream()
                    .filter(row -> !row.readyForWeight())
                    .map(row -> logicalPoLabel(row.po())
                            + " [SKU=" + (row.skuAssigned() ? "OK" : "MISSING")
                            + ", PACKED=" + row.finishedCartons() + "/" + row.totalCartons()
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
        assertScheduleFactory(schedule);
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
            int finished = (int) cartons.stream().filter(c -> c.getFinishedAt() != null || AppConstants.CartonStatus.FINISHED.equals(c.getStatus()) || AppConstants.CartonStatus.READY_TO_SHIP.equals(c.getStatus())).count();
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
        if (detail.totalCartons() > 0 && detail.remainingWeightCartons() == 0 && detail.failedCartons() == 0 && detail.passCartons() == detail.totalCartons()) {
            LocalDateTime now = LocalDateTime.now();
            schedule.setStatus(COMPLETED);
            schedule.setCompletedBy(RequestActor.current());
            schedule.setCompletedAt(now);
            schedule.setUpdatedBy(RequestActor.current());
            schedule.setUpdatedAt(now);
            addHistory(schedule, "COMPLETED", "All cartons passed Carton Weight. Shipping history retained with SSCC and weighing results.");
            scheduleRepository.save(schedule);
            audit(AppConstants.AuditAction.SHIPPING_SCHEDULE_COMPLETE, schedule.getId(), "Completed " + schedule.getScheduleNo());
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
