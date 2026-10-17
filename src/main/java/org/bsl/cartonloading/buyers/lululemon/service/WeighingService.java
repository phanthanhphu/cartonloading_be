package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.constants.AppMessages;
import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.es.service.PackingOrderService;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.importing.TextNormalizer;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.model.BuyerAccess;
import org.bsl.cartonloading.service.AuditLogService;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class WeighingService {

    /** OFF by default for legacy shipments; enable after RFID pilot/go-live. */
    private final PurchaseOrderRepository poRepository;
    private final CartonRepository cartonRepository;
    private final WeighingOrderRepository weighingOrderRepository;
    private final WeighingResultRepository weightEventRepository;
    private final ShippingScheduleRepository shippingScheduleRepository;
    private final PackingOrderService orderService;
    private final BuyerFactoryAccessService factoryAccess;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;

    public WeighingService(
            PurchaseOrderRepository poRepository,
            CartonRepository cartonRepository,
            WeighingOrderRepository weighingOrderRepository,
            WeighingResultRepository weightEventRepository,
            ShippingScheduleRepository shippingScheduleRepository,
            PackingOrderService orderService,
            BuyerFactoryAccessService factoryAccess,
            AuditLogService auditLogService,
            AppSocketPublisher socketPublisher
    ) {
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.weighingOrderRepository = weighingOrderRepository;
        this.weightEventRepository = weightEventRepository;
        this.shippingScheduleRepository = shippingScheduleRepository;
        this.orderService = orderService;
        this.factoryAccess = factoryAccess;
        this.auditLogService = auditLogService;
        this.socketPublisher = socketPublisher;
    }

    public List<WeighingEligiblePo> eligiblePos(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        List<WeighingEligiblePo> rows = new ArrayList<>();
        for (PurchaseOrder po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, AppConstants.Buyer.LULULEMON)) {
            if (!canAccessFactoryOrUnassigned(po.getFactoryCode())) continue;
            List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
            if (cartons.isEmpty()) continue;
            boolean ready = cartons.stream().allMatch(c -> CartonWeightReadiness.isReady(po, c));
            if (!ready) continue;
            rows.add(new WeighingEligiblePo(
                    po,
                    derivedWeightProfileOrNull(po),
                    cartons.size()
            ));
        }
        return rows;
    }

    /**
     * Backward-compatible endpoint: LULULEMON no longer uses a manually configured
     * Unit Weight / Tare / Tolerance profile. The authoritative standard is ALL_BP GW.
     */
    public WeightProfile saveProfile(
            String buyerCode, String orderId, String poId, WeightProfileRequest request
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        return derivedWeightProfile(po);
    }

    public List<WeighingOrder> listWeighingOrders(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        return weighingOrderRepository.findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .filter(this::canAccessWeighingOrder)
                .filter(this::isPackingDispatched)
                .toList();
    }

    /**
     * Retained as a no-op for old Weight clients. Only a Packing user using
     * ShippingScheduleService.sendToCartonWeight may authorize a hand-off.
     * Opening the Weight board or looking up an SSCC must never dispatch POs.
     */
    public int prepareAssignedSchedules(String buyerCode) {
        requireLululemonBuyer(buyerCode);
        return 0;
    }

    /** Only the explicitly sent weighing order belongs to the active Weight queue. */
    private boolean isPackingDispatched(WeighingOrder order) {
        String scheduleId = clean(order.getShippingScheduleId());
        if (scheduleId == null) return false;
        ShippingSchedule schedule = shippingScheduleRepository
                .findByIdAndOrderIdAndBuyerCode(scheduleId, order.getOrderId(), AppConstants.Buyer.LULULEMON)
                .orElse(null);
        return schedule != null && order.getId().equals(schedule.getWeighingOrderId())
                && schedule.getSentToWeightAt() != null
                && (ShippingScheduleService.SENT_TO_WEIGHT.equals(schedule.getStatus())
                    || ShippingScheduleService.WEIGHING.equals(schedule.getStatus())
                    || ShippingScheduleService.COMPLETED.equals(schedule.getStatus()));
    }

    /**
     * Station-facing queue. The weighing screen consumes this instead of rebuilding
     * progress from PO master data, so operators see only work that was already sent
     * to Carton Weight.
     */
    public List<WeighingQueueRow> queue(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        List<WeighingQueueRow> rows = new ArrayList<>();
        for (WeighingOrder order : listWeighingOrders(buyerCode, orderId)) {
            if (!isPackingDispatched(order)) continue;
            List<Carton> cartons = new ArrayList<>();
            if (order.getCartonIds() != null && !order.getCartonIds().isEmpty()) {
                cartonRepository.findAllById(order.getCartonIds()).forEach(cartons::add);
            }
            int pass = (int) cartons.stream().filter(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())).count();
            int failed = (int) cartons.stream().filter(c -> isFailedWeightStatus(c.getWeightStatus())).count();
            int remaining = Math.max(0, cartons.size() - pass - failed);
            rows.add(new WeighingQueueRow(order, cartons.size(), pass, failed, remaining));
        }
        return rows;
    }

    /**
     * Carton Weight board across all LULULEMON Orders. Keep completed weighing jobs
     * and completed Shipping Lists visible so the Order -> Shipping List -> PO ->
     * Carton drill-down retains the saved results after a successful weigh.
     *
     * This is a read-only listing. Starting/reweighing a completed PO is still
     * guarded by the dedicated write operations.
     */
    public List<CartonWeightAssignmentRow> stationAssignments(String buyerCode) {
        requireLululemonBuyer(buyerCode);
        List<CartonWeightAssignmentRow> rows = new ArrayList<>();
        Map<String, PackingOrder> orderCache = new HashMap<>();
        Map<String, PurchaseOrder> poCache = new HashMap<>();

        for (WeighingOrder weighingOrder : weighingOrderRepository.findByBuyerCodeOrderByCreatedAtDesc(AppConstants.Buyer.LULULEMON)) {
            // A completed weight job can still have RFID pending or failed.
            // Do not hide its Shipping List from the read-only station board.
            String scheduleId = clean(weighingOrder.getShippingScheduleId());
            if (scheduleId == null) continue;
            ShippingSchedule schedule = shippingScheduleRepository
                    .findByIdAndOrderIdAndBuyerCode(scheduleId, weighingOrder.getOrderId(), AppConstants.Buyer.LULULEMON)
                    .orElse(null);
            if (schedule == null || !factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode())) continue;
            if (!isPackingDispatched(weighingOrder)) continue;
            if (!(ShippingScheduleService.SENT_TO_WEIGHT.equals(schedule.getStatus())
                    || ShippingScheduleService.WEIGHING.equals(schedule.getStatus())
                    || ShippingScheduleService.COMPLETED.equals(schedule.getStatus()))) continue;

            PackingOrder packingOrder;
            try {
                packingOrder = orderCache.computeIfAbsent(weighingOrder.getOrderId(), id -> orderService.getEntity(AppConstants.Buyer.LULULEMON, id));
            } catch (RuntimeException ex) {
                continue;
            }

            List<Carton> cartons = new ArrayList<>();
            if (weighingOrder.getCartonIds() != null && !weighingOrder.getCartonIds().isEmpty()) {
                cartonRepository.findAllById(weighingOrder.getCartonIds()).forEach(cartons::add);
            }
            cartons.stream()
                    .sorted(Comparator.comparing(Carton::getPoNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(Carton::getCartonNo, Comparator.nullsLast(Comparator.naturalOrder())))
                    .forEach(carton -> {
                        PurchaseOrder po = poCache.computeIfAbsent(carton.getPoId(), id -> poRepository
                                .findByIdAndOrderIdAndBuyerCode(id, carton.getOrderId(), AppConstants.Buyer.LULULEMON)
                                .orElse(null));
                        int qty = carton.getScannedQty() != null && carton.getScannedQty() > 0
                                ? carton.getScannedQty()
                                : (carton.getPlannedQty() == null ? 0 : carton.getPlannedQty());
                        rows.add(new CartonWeightAssignmentRow(
                                packingOrder.getId(), packingOrder.getOrderName(), packingOrder.getOrderDate(),
                                schedule.getId(), schedule.getScheduleNo(), schedule.getFactoryCode(), schedule.getExFtyDate(),
                                weighingOrder.getId(), weighingOrder.getStatus(), carton.getPoId(),
                                po == null ? carton.getPoNumber() : po.getPoNumber(), po == null ? null : po.getSku(),
                                po == null ? null : po.getStyle(), po == null ? null : po.getColor(), po == null ? null : po.getSize(),
                                carton.getId(), carton.getCartonNo(), qty, carton.getSscc(), normalizeWeightStatus(carton.getWeightStatus()), carton.getRfidStatus(),
                                carton.getWeightStationCode(), carton.getWeighedAt()
                        ));
                    });
        }
        return rows;
    }

    /**
     * Scan-first Carton Weight lookup across every Order. SSCC must belong to a
     * Shipping Schedule already confirmed by Packing and assigned to Carton Weight.
     */
    public WeighingLookupResponse lookupAssignedSscc(String buyerCode, WeighingLookupRequest request) {
        requireLululemonBuyer(buyerCode);
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException(AppMessages.VALID_SSCC_REQUIRED);
        Carton carton = cartonRepository.findBySscc18(sscc)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.unknownSscc(sscc)));
        if (!AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode())) {
            throw new IllegalArgumentException(AppMessages.unknownSscc(sscc));
        }
        assertFactoryAccessIfPresent(carton.getFactoryCode());

        WeighingOrder weighingOrder = weighingOrderRepository
                .findByBuyerCodeOrderByCreatedAtDesc(AppConstants.Buyer.LULULEMON)
                .stream()
                .filter(row -> !AppConstants.WeighingOrderStatus.COMPLETED.equals(row.getStatus()))
                .filter(row -> row.getCartonIds() != null && row.getCartonIds().contains(carton.getId()))
                .filter(this::canAccessWeighingOrder)
                .filter(this::isPackingDispatched)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(assignmentLookupProblem(carton)));

        String scheduleId = clean(weighingOrder.getShippingScheduleId());
        ShippingSchedule schedule = scheduleId == null ? null : shippingScheduleRepository
                .findByIdAndOrderIdAndBuyerCode(scheduleId, weighingOrder.getOrderId(), AppConstants.Buyer.LULULEMON)
                .orElse(null);
        if (schedule == null || !(ShippingScheduleService.SENT_TO_WEIGHT.equals(schedule.getStatus())
                || ShippingScheduleService.WEIGHING.equals(schedule.getStatus()))) {
            throw new IllegalArgumentException(assignmentLookupProblem(carton));
        }
        PurchaseOrder po = requirePo(carton.getOrderId(), carton.getPoId());
        WeightProfile profile = derivedWeightProfile(po);
        BigDecimal expected = expectedWeight(carton, profile);
        PackingOrder packingOrder = orderService.getEntity(AppConstants.Buyer.LULULEMON, carton.getOrderId());
        String message = clean(carton.getWeightStatus()) == null
                ? "SSCC-18 found in Shipping List assigned to Carton Weight."
                : "SSCC-18 found. Current weight status: " + normalizeWeightStatus(carton.getWeightStatus()) + ".";
        return new WeighingLookupResponse(
                weighingOrder, po, carton, profile, expected, message, packingOrder, schedule
        );
    }

    /** Tell the operator which hand-off is missing; never treat an identified SSCC as unknown. */
    private String assignmentLookupProblem(Carton carton) {
        List<ShippingSchedule> schedules = shippingScheduleRepository
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(carton.getOrderId(), AppConstants.Buyer.LULULEMON)
                .stream()
                .filter(schedule -> schedule.getPoIds() != null && schedule.getPoIds().contains(carton.getPoId()))
                .filter(schedule -> factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode()))
                .toList();
        if (schedules.isEmpty()) {
            return "SSCC " + carton.getSscc18() + " exists in Packing, but its PO has no accessible Shipping Schedule. Assign this PO to a Shipping Schedule first.";
        }
        ShippingSchedule schedule = schedules.get(0);
        if (ShippingScheduleService.PLANNED.equals(schedule.getStatus())
                || ShippingScheduleService.REVIEWED.equals(schedule.getStatus())) {
            return "SSCC " + carton.getSscc18() + " belongs to Shipping List " + schedule.getScheduleNo()
                    + " but the list is not ready for Carton Weight. Confirm SSCC, planned Qty and label SKU "
                    + "for EVERY carton in its POs. Then Packing must SEND TO WEIGHT. "
                    + "Weight cannot accept the carton before Packing sends its Shipment.";
        }
        if (ShippingScheduleService.COMPLETED.equals(schedule.getStatus())) {
            return "Shipping List " + schedule.getScheduleNo() + " is already completed. Check Weight History instead of starting another scan.";
        }
        return "SSCC " + carton.getSscc18() + " is in Shipping List " + schedule.getScheduleNo()
                + ", but no active weighing assignment contains this carton. Refresh or ask IT to reconcile the Shipping List hand-off.";
    }

    /** Full Carton Weight history list for management/search screens. */
    public List<CartonWeightHistoryRow> stationHistory(String buyerCode) {
        requireLululemonBuyer(buyerCode);
        Map<String, ShippingSchedule> scheduleCache = new HashMap<>();
        Map<String, PackingOrder> orderCache = new HashMap<>();
        List<CartonWeightHistoryRow> rows = new ArrayList<>();
        for (WeighingResult result : weightEventRepository.findByBuyerCodeOrderByCreatedAtDesc(AppConstants.Buyer.LULULEMON)) {
            String scheduleId = clean(result.getShippingScheduleId());
            if (scheduleId == null) continue;
            String scheduleKey = result.getOrderId() + "::" + scheduleId;
            ShippingSchedule schedule = scheduleCache.computeIfAbsent(scheduleKey, key -> shippingScheduleRepository
                    .findByIdAndOrderIdAndBuyerCode(scheduleId, result.getOrderId(), AppConstants.Buyer.LULULEMON)
                    .orElse(null));
            if (schedule == null || !factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode())) continue;
            PackingOrder order;
            try {
                order = orderCache.computeIfAbsent(result.getOrderId(), id -> orderService.getEntity(AppConstants.Buyer.LULULEMON, id));
            } catch (RuntimeException ex) {
                order = null;
            }
            rows.add(new CartonWeightHistoryRow(
                    result,
                    order == null ? result.getOrderId() : order.getOrderName(),
                    schedule.getScheduleNo(), schedule.getFactoryCode(), schedule.getExFtyDate()
            ));
        }
        return rows;
    }

    public synchronized PurchaseOrder startPoWeighingSession(
            String buyerCode, String orderId, String poId, WeighingSessionRequest request
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        String station = clean(request == null ? null : request.stationCode());
        if (station == null) throw new IllegalArgumentException(AppMessages.required(AppConstants.FieldLabel.STATION_DEVICE_CODE));

        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        final PurchaseOrder readinessPo = po;
        if (cartons.isEmpty() || cartons.stream().anyMatch(c -> !CartonWeightReadiness.isReady(readinessPo, c))) {
            throw new IllegalArgumentException(AppMessages.poNotReadyForWeighing(po.getPoNumber()));
        }
        WeighingOrder queueOrder = weighingOrderRepository
                .findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream()
                .filter(row -> row.getPoIds() != null && row.getPoIds().contains(poId))
                .filter(this::isPackingDispatched)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.WEIGHING_PO_NOT_IN_QUEUE));
        if (AppConstants.WeighingSessionStatus.COMPLETED.equals(po.getWeighingSessionStatus())
                || cartons.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())
                    && "PASS".equals(c.getRfidStatus()))) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_SESSION_COMPLETED);
        }

        LocalDateTime now = LocalDateTime.now();
        po.setWeighingSessionStatus(AppConstants.WeighingSessionStatus.RUNNING);
        po.setWeighingStationCode(station);
        po.setWeighingStartedBy(RequestActor.current());
        po.setWeighingStartedAt(now);
        po.setWeighingStoppedBy(null);
        po.setWeighingStoppedAt(null);
        po.setWeighingCompletedBy(null);
        po.setWeighingCompletedAt(null);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(now);
        po = poRepository.save(po);
        markShippingScheduleWeighing(orderId, queueOrder.getShippingScheduleId());
        audit(AppConstants.AuditAction.WEIGHING_SESSION_START, poId,
                "Started Carton Weighing for logical PO " + po.getPoNumber() + " at station " + station);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHING_SESSION_STARTED, poId);
        return po;
    }

    public synchronized PurchaseOrder stopPoWeighingSession(
            String buyerCode, String orderId, String poId
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        LocalDateTime now = LocalDateTime.now();
        po.setWeighingSessionStatus(AppConstants.WeighingSessionStatus.STOPPED);
        po.setWeighingStoppedBy(RequestActor.current());
        po.setWeighingStoppedAt(now);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(now);
        po = poRepository.save(po);
        audit(AppConstants.AuditAction.WEIGHING_SESSION_STOP, poId,
                "Stopped Carton Weighing for logical PO " + po.getPoNumber());
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHING_SESSION_STOPPED, poId);
        return po;
    }

    public synchronized PurchaseOrder completePoWeighingSession(
            String buyerCode, String orderId, String poId
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        assertFactoryAccessIfPresent(po.getFactoryCode());
        List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
        if (cartons.isEmpty() || cartons.stream().anyMatch(c -> clean(c.getWeightStatus()) == null)) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_PO_HAS_REMAINING);
        }
        if (cartons.stream().anyMatch(c -> isFailedWeightStatus(c.getWeightStatus()))) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_PO_HAS_FAILED);
        }
        if (cartons.stream().anyMatch(c -> !AppConstants.WeightStatus.PASS.equals(c.getWeightStatus()))) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_PO_HAS_REMAINING);
        }

        // Weight session may finish independently. RFID exceptions remain visible
        // and must never be presented as a fully verified carton/shipment.
        String rfidReview = RfidReviewStatus.fromCartons(cartons);
        LocalDateTime now = LocalDateTime.now();
        po.setWeighingSessionStatus(AppConstants.WeighingSessionStatus.COMPLETED);
        po.setRfidReviewStatus(rfidReview);
        po.setWeighingCompletedBy(RequestActor.current());
        po.setWeighingCompletedAt(now);
        po.setWeighingStoppedBy(RequestActor.current());
        po.setWeighingStoppedAt(now);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(now);
        po = poRepository.save(po);
        findWeighingOrderForPo(orderId, poId).ifPresent(order -> {
            refreshWeighingOrder(order);
            refreshShippingScheduleCompletion(orderId, order.getShippingScheduleId());
        });
        audit(AppConstants.AuditAction.WEIGHING_SESSION_COMPLETE, poId,
                "Completed Carton Weighing for logical PO " + po.getPoNumber() + " with " + cartons.size()
                        + " weight PASS carton(s); RFID review=" + rfidReview);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHING_SESSION_COMPLETED, poId);
        return po;
    }

    /** Called only by Packing's explicit SEND TO WEIGHT hand-off (automatic readiness check). */
    public synchronized WeighingOrder createWeighingOrder(
            String buyerCode, String orderId, WeighingOrderCreateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LinkedHashSet<String> poIds = new LinkedHashSet<>(request == null || request.poIds() == null ? List.of() : request.poIds());
        poIds.removeIf(id -> clean(id) == null);
        if (poIds.isEmpty()) throw new IllegalArgumentException(AppMessages.SELECT_PO_FOR_WEIGHING);

        String shippingScheduleId = clean(request == null ? null : request.shippingScheduleId());
        if (shippingScheduleId == null) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_REQUIRES_SHIPPING_SCHEDULE);
        }
        ShippingSchedule schedule = shippingScheduleRepository
                .findByIdAndOrderIdAndBuyerCode(shippingScheduleId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("Shipping Schedule not found."));
        // No separate CHECK button: ShippingScheduleService validates readiness
        // on SEND, then calls this method. The direct Weight /orders API remains blocked.
        if (!ShippingScheduleService.PLANNED.equals(schedule.getStatus())
                && !ShippingScheduleService.REVIEWED.equals(schedule.getStatus())) {
            throw new IllegalArgumentException("Shipment is not available for Packing dispatch.");
        }
        Set<String> scheduled = new LinkedHashSet<>(schedule.getPoIds() == null ? List.of() : schedule.getPoIds());
        if (!scheduled.equals(poIds)) {
            throw new IllegalArgumentException("Carton Weight must receive exactly the logical POs assigned to the selected Shipping Schedule.");
        }

        String factory = null;
        LinkedHashSet<String> cartonIds = new LinkedHashSet<>();
        for (String poId : poIds) {
            PurchaseOrder po = requirePo(orderId, poId);
            assertFactoryAccessIfPresent(po.getFactoryCode());
            String poFactory = normalizeFactory(po.getFactoryCode());
            if (poFactory != null) {
                if (factory == null) factory = poFactory;
                else if (!Objects.equals(normalizeFactory(factory), poFactory)) {
                    throw new IllegalArgumentException(AppMessages.WEIGHING_SINGLE_FACTORY);
                }
            }
            // Transfer is based on identity, NOT weight or RFID completion.
            // The actual scale lookup later validates ALL_BP GW before weighing.
            List<Carton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
            if (cartons.isEmpty() || cartons.stream().anyMatch(c -> !CartonWeightReadiness.isReady(po, c))) {
                throw new IllegalArgumentException(AppMessages.poNotReadyForWeighing(po.getPoNumber()));
            }
            cartons.forEach(c -> cartonIds.add(c.getId()));
        }

        List<WeighingOrder> existingOpen = weighingOrderRepository.findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON).stream()
                .filter(row -> !AppConstants.WeighingOrderStatus.COMPLETED.equals(row.getStatus()))
                .toList();
        for (WeighingOrder open : existingOpen) {
            if (open.getCartonIds() == null) continue;
            for (String cartonId : cartonIds) {
                if (open.getCartonIds().contains(cartonId)) {
                    throw new IllegalArgumentException(AppMessages.cartonAlreadyInWeighingOrder(open.getName()));
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        WeighingOrder order = new WeighingOrder();
        order.setBuyerCode(AppConstants.Buyer.LULULEMON);
        order.setOrderId(orderId);
        String name = clean(request == null ? null : request.name());
        order.setName(name == null ? "Weighing " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : name);
        order.setFactoryCode(factory);
        order.setShippingScheduleId(shippingScheduleId);
        order.setPoIds(new ArrayList<>(poIds));
        order.setCartonIds(new ArrayList<>(cartonIds));
        order.setStatus(AppConstants.WeighingOrderStatus.WAITING_FOR_WEIGHING);
        order.setCreatedBy(RequestActor.current());
        order.setUpdatedBy(RequestActor.current());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = weighingOrderRepository.save(order);
        audit(AppConstants.AuditAction.WEIGHING_ORDER_CREATE, order.getId(),
                "Created Weighing Order " + order.getName() + " with " + poIds.size() + " PO(s) / " + cartonIds.size() + " carton(s)");
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHING_ORDER_CREATED, order.getId());
        return order;
    }

    public WeighingLookupResponse lookup(
            String buyerCode, String orderId, String weighingOrderId, WeighingLookupRequest request
    ) {
        requireOrder(buyerCode, orderId);
        WeighingOrder weighingOrder = requireWeighingOrder(orderId, weighingOrderId);
        assertFactoryAccessIfPresent(weighingOrder.getFactoryCode());
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException(AppMessages.VALID_SSCC_REQUIRED);
        Carton carton = cartonRepository.findBySscc18(sscc)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.unknownSscc(sscc)));
        return buildLookup(orderId, weighingOrder, carton);
    }

    /**
     * Scan-first station lookup. The operator does not need to know the Weighing Order;
     * the SSCC uniquely identifies the carton and the backend resolves its open queue job.
     */
    public WeighingLookupResponse lookupAny(
            String buyerCode, String orderId, WeighingLookupRequest request
    ) {
        requireOrder(buyerCode, orderId);
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException(AppMessages.VALID_SSCC_REQUIRED);
        Carton carton = cartonRepository.findBySscc18(sscc)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.unknownSscc(sscc)));
        if (!Objects.equals(orderId, carton.getOrderId()) || !AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode())) {
            throw new IllegalArgumentException(AppMessages.unknownSscc(sscc));
        }
        assertFactoryAccessIfPresent(carton.getFactoryCode());

        WeighingOrder weighingOrder = findWeighingOrderForCarton(orderId, carton.getId());
        return buildLookup(orderId, weighingOrder, carton);
    }

    /** PO-scoped station lookup used after the operator selects a logical PO and presses START. */
    public WeighingLookupResponse lookupForPo(
            String buyerCode, String orderId, String poId, WeighingLookupRequest request
    ) {
        requireOrder(buyerCode, orderId);
        PurchaseOrder po = requirePo(orderId, poId);
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException(AppMessages.VALID_SSCC_REQUIRED);
        Carton carton = cartonRepository.findBySscc18(sscc)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.unknownSscc(sscc)));
        if (!Objects.equals(poId, carton.getPoId())) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_SSCC_WRONG_PO);
        }
        // A saved Weight PASS must remain immutable while the operator reviews/rescans RFID.
        // This also works when the PO station has been STOPPED but not yet marked COMPLETED.
        // A weight FAILED/PENDING carton is NOT eligible: normal station/weighing checks apply.
        boolean rfidOnlyReview = AppConstants.WeightStatus.PASS.equals(carton.getWeightStatus())
                && !"PASS".equals(carton.getRfidStatus());
        if (!rfidOnlyReview) requireRunningWeighingSession(po);
        WeighingOrder weighingOrder = findWeighingOrderForCarton(orderId, carton.getId());
        return buildLookup(orderId, weighingOrder, carton, rfidOnlyReview);
    }

    private WeighingLookupResponse buildLookup(String orderId, WeighingOrder weighingOrder, Carton carton) {
        return buildLookup(orderId, weighingOrder, carton, false);
    }

    private WeighingLookupResponse buildLookup(String orderId, WeighingOrder weighingOrder, Carton carton, boolean rfidOnlyReview) {
        validateCartonForOrder(weighingOrder, carton);
        if (!rfidOnlyReview && clean(carton.getWeightStatus()) != null) {
            String displayStatus = isFailedWeightStatus(carton.getWeightStatus())
                    ? AppConstants.WeightStatus.FAILED
                    : carton.getWeightStatus();
            throw new IllegalArgumentException(AppMessages.cartonAlreadyWeighed(carton.getCartonNo(), displayStatus));
        }
        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        WeightProfile profile = derivedWeightProfile(po);
        BigDecimal expected = expectedWeight(carton, profile);
        PackingOrder packingOrder = orderService.getEntity(AppConstants.Buyer.LULULEMON, orderId);
        ShippingSchedule shippingSchedule = clean(weighingOrder.getShippingScheduleId()) == null ? null : shippingScheduleRepository
                .findByIdAndOrderIdAndBuyerCode(weighingOrder.getShippingScheduleId(), orderId, AppConstants.Buyer.LULULEMON)
                .orElse(null);
        return new WeighingLookupResponse(
                weighingOrder, po, carton, profile, expected,
                AppMessages.SSCC_ACCEPTED_FOR_WEIGHING,
                packingOrder, shippingSchedule
        );
    }

    public synchronized WeightSubmitResponse submitWeight(
            String buyerCode, String orderId, String weighingOrderId, WeightSubmitRequest request
    ) {
        if (request == null) throw new IllegalArgumentException(AppMessages.required(AppConstants.FieldLabel.WEIGHT_DATA));
        if (!Boolean.TRUE.equals(request.stable())) {
            throw new IllegalArgumentException(AppMessages.SCALE_NOT_STABLE);
        }
        String station = clean(request.stationCode());
        if (station == null) throw new IllegalArgumentException(AppMessages.required(AppConstants.FieldLabel.STATION_DEVICE_CODE));
        BigDecimal actual = positive(request.actualWeightKg(), "Actual Weight").setScale(3, RoundingMode.HALF_UP);

        WeighingLookupResponse lookup = lookup(
                buyerCode, orderId, weighingOrderId, new WeighingLookupRequest(request.sscc())
        );
        Carton carton = lookup.carton();
        PurchaseOrder po = lookup.po();
        requireRunningWeighingSession(po);
        WeightProfile profile = lookup.profile();
        BigDecimal expected = lookup.expectedWeightKg();
        BigDecimal difference = actual.subtract(expected).setScale(3, RoundingMode.HALF_UP);
        BigDecimal tolerance = profile.getToleranceKg().setScale(3, RoundingMode.HALF_UP);
        String result = difference.abs().compareTo(tolerance) <= 0 ? AppConstants.WeightStatus.PASS : AppConstants.WeightStatus.FAILED;
        LocalDateTime now = LocalDateTime.now();

        carton.setExpectedWeightKg(expected);
        carton.setActualWeightKg(actual);
        carton.setWeightDifferenceKg(difference);
        carton.setWeightToleranceKg(tolerance);
        carton.setWeightStatus(result);
        carton.setWeightStationCode(station);
        carton.setWeighedBy(RequestActor.current());
        carton.setWeighedAt(now);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(now);
        carton = cartonRepository.save(carton);

        WeighingResult event = new WeighingResult();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(orderId);
        event.setWeighingOrderId(weighingOrderId);
        event.setShippingScheduleId(lookup.weighingOrder().getShippingScheduleId());
        event.setPoId(po.getId());
        event.setPoNumber(po.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setCartonQty(carton.getScannedQty() != null && carton.getScannedQty() > 0 ? carton.getScannedQty() : carton.getPlannedQty());
        event.setSscc18(carton.getSscc());
        event.setAction(AppConstants.WeightAction.WEIGH);
        event.setExpectedWeightKg(expected);
        event.setActualWeightKg(actual);
        event.setDifferenceKg(difference);
        event.setToleranceKg(tolerance);
        event.setResult(result);
        event.setStationCode(station);
        event.setSource(normalizeSource(request.source()));
        event.setStable(true);
        event.setUserId(RequestActor.current());
        event.setCreatedAt(now);
        event = weightEventRepository.save(event);

        WeighingOrder order = refreshWeighingOrder(requireWeighingOrder(orderId, weighingOrderId));
        audit(AppConstants.AuditAction.WEIGHT_CAPTURE, carton.getId(),
                "Weighed PO " + po.getPoNumber() + " carton " + carton.getCartonNo() + " SSCC " + carton.getSscc()
                        + ": expected=" + expected + "kg actual=" + actual + "kg result=" + result + " station=" + station);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_RESULT_PREFIX + result, carton.getId());
        String message = AppConstants.WeightStatus.PASS.equals(result)
                ? AppMessages.WEIGHING_PASS_COMPLETED
                : AppMessages.WEIGHING_FAILED;
        return new WeightSubmitResponse(order, carton, event, message);
    }

    /** Resolve the Weighing Order from SSCC, then store a stable PLC reading. */
    public synchronized WeightSubmitResponse submitWeightAny(
            String buyerCode, String orderId, WeightSubmitRequest request
    ) {
        if (request == null) throw new IllegalArgumentException(AppMessages.required(AppConstants.FieldLabel.WEIGHT_DATA));
        WeighingLookupResponse resolved = lookupAny(buyerCode, orderId, new WeighingLookupRequest(request.sscc()));
        return submitWeight(buyerCode, orderId, resolved.weighingOrder().getId(), request);
    }

    public synchronized WeightSubmitResponse submitWeightForPo(
            String buyerCode, String orderId, String poId, WeightSubmitRequest request
    ) {
        if (request == null) throw new IllegalArgumentException(AppMessages.required(AppConstants.FieldLabel.WEIGHT_DATA));
        WeighingLookupResponse resolved = lookupForPo(buyerCode, orderId, poId, new WeighingLookupRequest(request.sscc()));
        return submitWeight(buyerCode, orderId, resolved.weighingOrder().getId(), request);
    }

    public synchronized Carton reopenWeight(
            String buyerCode, String orderId, String weighingOrderId, String cartonId, ExceptionRequest request
    ) {
        requireOrder(buyerCode, orderId);
        WeighingOrder order = requireWeighingOrder(orderId, weighingOrderId);
        Carton carton = cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound(AppConstants.Buyer.LULULEMON + " carton")));
        validateCartonForOrder(order, carton);
        String reason = clean(request == null ? null : request.reason());
        if (reason == null || reason.length() < 3) throw new IllegalArgumentException(AppMessages.REWEIGH_REASON_REQUIRED);
        if (clean(carton.getWeightStatus()) == null) throw new IllegalArgumentException(AppMessages.CARTON_NOT_WEIGHED);

        WeighingResult event = new WeighingResult();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(orderId);
        event.setWeighingOrderId(weighingOrderId);
        event.setShippingScheduleId(order.getShippingScheduleId());
        event.setPoId(carton.getPoId());
        event.setPoNumber(carton.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setCartonQty(carton.getScannedQty() != null && carton.getScannedQty() > 0 ? carton.getScannedQty() : carton.getPlannedQty());
        event.setSscc18(carton.getSscc());
        event.setAction(AppConstants.WeightAction.REOPEN);
        event.setExpectedWeightKg(carton.getExpectedWeightKg());
        event.setActualWeightKg(carton.getActualWeightKg());
        event.setDifferenceKg(carton.getWeightDifferenceKg());
        event.setToleranceKg(carton.getWeightToleranceKg());
        event.setResult(null);
        event.setStationCode(carton.getWeightStationCode());
        event.setReason(reason);
        event.setUserId(RequestActor.current());
        event.setCreatedAt(LocalDateTime.now());
        weightEventRepository.save(event);

        carton.setExpectedWeightKg(null);
        carton.setActualWeightKg(null);
        carton.setWeightDifferenceKg(null);
        carton.setWeightToleranceKg(null);
        carton.setWeightStatus(null);
        carton.setWeightStationCode(null);
        carton.setWeighedBy(null);
        carton.setWeighedAt(null);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(LocalDateTime.now());
        carton = cartonRepository.save(carton);
        PurchaseOrder po = requirePo(orderId, carton.getPoId());
        po.setWeighingSessionStatus(AppConstants.WeighingSessionStatus.STOPPED);
        po.setWeighingCompletedBy(null);
        po.setWeighingCompletedAt(null);
        po.setUpdatedBy(RequestActor.current());
        po.setUpdatedAt(LocalDateTime.now());
        poRepository.save(po);
        refreshWeighingOrder(order);
        audit(AppConstants.AuditAction.REWEIGH_OPEN, cartonId,
                AppMessages.reweighOpenedAudit(carton.getPoNumber(), carton.getCartonNo(), reason));
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_REOPENED, cartonId);
        return carton;
    }

    public synchronized Carton overrideWeightPass(
            String buyerCode, String orderId, String weighingOrderId, String cartonId, ExceptionRequest request
    ) {
        requireOrder(buyerCode, orderId);
        WeighingOrder order = requireWeighingOrder(orderId, weighingOrderId);
        Carton carton = cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound(AppConstants.Buyer.LULULEMON + " carton")));
        validateCartonForOrder(order, carton);
        String reason = clean(request == null ? null : request.reason());
        if (reason == null || reason.length() < 3) throw new IllegalArgumentException(AppMessages.OVERRIDE_REASON_REQUIRED);
        if (!isFailedWeightStatus(carton.getWeightStatus())) {
            throw new IllegalArgumentException(AppMessages.CARTON_NOT_FAILED);
        }

        LocalDateTime now = LocalDateTime.now();
        WeighingResult event = new WeighingResult();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(orderId);
        event.setWeighingOrderId(weighingOrderId);
        event.setShippingScheduleId(order.getShippingScheduleId());
        event.setPoId(carton.getPoId());
        event.setPoNumber(carton.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setCartonQty(carton.getScannedQty() != null && carton.getScannedQty() > 0 ? carton.getScannedQty() : carton.getPlannedQty());
        event.setSscc18(carton.getSscc());
        event.setAction(AppConstants.WeightAction.OVERRIDE);
        event.setExpectedWeightKg(carton.getExpectedWeightKg());
        event.setActualWeightKg(carton.getActualWeightKg());
        event.setDifferenceKg(carton.getWeightDifferenceKg());
        event.setToleranceKg(carton.getWeightToleranceKg());
        event.setResult(AppConstants.WeightStatus.PASS);
        event.setStationCode(carton.getWeightStationCode());
        event.setSource(AppConstants.WeightSource.MANUAL);
        event.setReason(reason);
        event.setUserId(RequestActor.current());
        event.setCreatedAt(now);
        weightEventRepository.save(event);

        carton.setWeightStatus(AppConstants.WeightStatus.PASS);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(now);
        carton = cartonRepository.save(carton);
        refreshWeighingOrder(order);
        audit(AppConstants.AuditAction.WEIGHT_OVERRIDE, cartonId,
                "Overrode FAILED to PASS for PO " + carton.getPoNumber() + " carton " + carton.getCartonNo() + ": " + reason);
        socketPublisher.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_OVERRIDDEN, cartonId);
        return carton;
    }

    public List<WeighingResult> history(String buyerCode, String orderId, String weighingOrderId) {
        requireOrder(buyerCode, orderId);
        WeighingOrder order = requireWeighingOrder(orderId, weighingOrderId);
        assertFactoryAccessIfPresent(order.getFactoryCode());
        return weightEventRepository.findByWeighingOrderIdOrderByCreatedAtDesc(weighingOrderId);
    }

    private WeighingOrder refreshWeighingOrder(WeighingOrder order) {
        List<Carton> cartons = new ArrayList<>();
        if (order.getCartonIds() != null) cartonRepository.findAllById(order.getCartonIds()).forEach(cartons::add);
        String status;
        // Weight-order completion is independent of RFID final verification.
        if (!cartons.isEmpty() && cartons.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus()))) {
            status = AppConstants.WeighingOrderStatus.COMPLETED;
        } else if (cartons.stream().anyMatch(c -> isFailedWeightStatus(c.getWeightStatus()))) {
            status = AppConstants.WeighingOrderStatus.FAILED;
        } else if (cartons.stream().anyMatch(c -> clean(c.getWeightStatus()) != null)) {
            status = AppConstants.WeighingOrderStatus.IN_PROGRESS;
        } else {
            status = AppConstants.WeighingOrderStatus.WAITING_FOR_WEIGHING;
        }
        order.setStatus(status);
        order.setUpdatedBy(RequestActor.current());
        order.setUpdatedAt(LocalDateTime.now());
        return weighingOrderRepository.save(order);
    }

    private void validateCartonForOrder(WeighingOrder order, Carton carton) {
        if (!isPackingDispatched(order)) {
            throw new IllegalArgumentException("Shipment has not been sent by Packing to Carton Weight.");
        }
        assertFactoryAccessIfPresent(carton.getFactoryCode());
        if (!Objects.equals(order.getOrderId(), carton.getOrderId()) || order.getCartonIds() == null || !order.getCartonIds().contains(carton.getId())) {
            throw new IllegalArgumentException(AppMessages.CARTON_NOT_IN_WEIGHING_ORDER);
        }
        if (!CartonWeightReadiness.isReady(requirePo(carton.getOrderId(), carton.getPoId()), carton)) {
            throw new IllegalArgumentException(AppMessages.CARTON_NOT_READY_FOR_WEIGHING);
        }
    }

    private BigDecimal expectedWeight(Carton carton, WeightProfile profile) {
        int actualQty = carton.getScannedQty() != null && carton.getScannedQty() > 0
                ? carton.getScannedQty()
                : (carton.getPlannedQty() == null ? 0 : carton.getPlannedQty());
        BigDecimal qty = BigDecimal.valueOf(actualQty);
        return qty.multiply(profile.getUnitWeightKg()).setScale(3, RoundingMode.HALF_UP);
    }

    private WeightProfile derivedWeightProfile(PurchaseOrder po) {
        if (po.getGrossWeightKg() == null || po.getGrossWeightKg() <= 0) {
            throw new IllegalArgumentException(AppMessages.grossWeightMissing(po.getPoNumber()));
        }
        BigDecimal gw = BigDecimal.valueOf(po.getGrossWeightKg()).setScale(4, RoundingMode.HALF_UP);
        WeightProfile profile = new WeightProfile();
        profile.setBuyerCode(AppConstants.Buyer.LULULEMON);
        profile.setOrderId(po.getOrderId());
        profile.setPoId(po.getId());
        profile.setPoNumber(po.getPoNumber());
        profile.setSku(po.getSku());
        profile.setUnitWeightKg(gw);
        profile.setTareWeightKg(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        profile.setToleranceKg(gw);
        return profile;
    }

    private WeightProfile derivedWeightProfileOrNull(PurchaseOrder po) {
        try {
            return derivedWeightProfile(po);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(AppMessages.mustBeGreaterThanZero(label));
        return value;
    }

    private WeighingOrder findWeighingOrderForCarton(String orderId, String cartonId) {
        return weighingOrderRepository
                .findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream()
                .filter(row -> row.getCartonIds() != null && row.getCartonIds().contains(cartonId))
                .filter(this::isPackingDispatched)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.SSCC_NOT_IN_OPEN_WEIGHING_ORDER));
    }

    private Optional<WeighingOrder> findWeighingOrderForPo(String orderId, String poId) {
        return weighingOrderRepository.findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)
                .stream().filter(row -> row.getPoIds() != null && row.getPoIds().contains(poId))
                .filter(this::isPackingDispatched).findFirst();
    }

    private void markShippingScheduleWeighing(String orderId, String scheduleId) {
        if (clean(scheduleId) == null) return;
        shippingScheduleRepository.findByIdAndOrderIdAndBuyerCode(scheduleId, orderId, AppConstants.Buyer.LULULEMON).ifPresent(schedule -> {
            if (ShippingScheduleService.SENT_TO_WEIGHT.equals(schedule.getStatus())) {
                schedule.setStatus(ShippingScheduleService.WEIGHING);
                schedule.setUpdatedBy(RequestActor.current());
                schedule.setUpdatedAt(LocalDateTime.now());
                if (schedule.getHistory() == null) schedule.setHistory(new ArrayList<>());
                schedule.getHistory().add(new ShippingScheduleHistoryEntry("WEIGHING_STARTED", RequestActor.current(),
                        "Carton Weight started weighing this Shipping Schedule.", LocalDateTime.now()));
                shippingScheduleRepository.save(schedule);
            }
        });
    }

    private void refreshShippingScheduleCompletion(String orderId, String scheduleId) {
        if (clean(scheduleId) == null) return;
        ShippingSchedule schedule = shippingScheduleRepository
                .findByIdAndOrderIdAndBuyerCode(scheduleId, orderId, AppConstants.Buyer.LULULEMON).orElse(null);
        if (schedule == null || ShippingScheduleService.COMPLETED.equals(schedule.getStatus())) return;
        List<Carton> all = new ArrayList<>();
        for (String poId : schedule.getPoIds() == null ? List.<String>of() : schedule.getPoIds()) {
            all.addAll(cartonRepository.findByPoIdOrderByCartonNoAsc(poId));
        }
        String rfidReview = RfidReviewStatus.fromCartons(all);
        boolean reviewChanged = !Objects.equals(schedule.getRfidReviewStatus(), rfidReview);
        schedule.setRfidReviewStatus(rfidReview);
        // Shipping FINAL is NEVER granted on weight alone, even if a legacy config disables RFID enforcement.
        if (!all.isEmpty() && all.stream().allMatch(c -> AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())
                && "PASS".equals(c.getRfidStatus()))) {
            LocalDateTime now = LocalDateTime.now();
            schedule.setStatus(ShippingScheduleService.COMPLETED);
            schedule.setCompletedBy(RequestActor.current());
            schedule.setCompletedAt(now);
            schedule.setUpdatedBy(RequestActor.current());
            schedule.setUpdatedAt(now);
            if (schedule.getHistory() == null) schedule.setHistory(new ArrayList<>());
            schedule.getHistory().add(new ShippingScheduleHistoryEntry("COMPLETED", RequestActor.current(),
                    "All cartons passed Carton Weight. Weighing and shipping history retained.", now));
            shippingScheduleRepository.save(schedule);
            auditLogService.log(RequestActor.current(), AppConstants.AuditAction.SHIPPING_SCHEDULE_COMPLETE,
                    AppConstants.AuditResource.SHIPPING_SCHEDULE, schedule.getId(), "Completed " + schedule.getScheduleNo()
                            + "; RFID review=" + rfidReview, null, null);
        } else if (reviewChanged) {
            shippingScheduleRepository.save(schedule);
        }
    }

    private void requireRunningWeighingSession(PurchaseOrder po) {
        if (!AppConstants.WeighingSessionStatus.RUNNING.equals(clean(po.getWeighingSessionStatus()))) {
            throw new IllegalArgumentException(AppMessages.WEIGHING_SESSION_NOT_RUNNING);
        }
    }

    /** Compatibility reader for data created before FAILED replaced the old HOLD label. */
    private boolean isFailedWeightStatus(String value) {
        String normalized = clean(value);
        return AppConstants.WeightStatus.FAILED.equals(normalized) || "HOLD".equals(normalized);
    }

    private String normalizeSscc(String value) {
        return SsccNormalizer.normalize(value);
    }

    private String normalizeSource(String value) {
        String v = clean(value);
        if (v == null) return AppConstants.WeightSource.MANUAL;
        v = v.toUpperCase(Locale.ROOT);
        return AppConstants.WeightSource.ALLOWED.contains(v) ? v : AppConstants.WeightSource.MANUAL;
    }

    private String normalizeFactory(String value) {
        String v = clean(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private boolean canAccessFactoryOrUnassigned(String factoryCode) {
        String normalized = normalizeFactory(factoryCode);
        return normalized == null || factoryAccess.canAccessWeighingFactory(normalized);
    }

    private void assertFactoryAccessIfPresent(String factoryCode) {
        String normalized = normalizeFactory(factoryCode);
        if (normalized != null) factoryAccess.assertWeighingFactoryAccess(normalized);
    }

    private void requireLululemonBuyer(String buyerCode) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!AppConstants.Buyer.LULULEMON.equals(buyer)) {
            throw new IllegalArgumentException(AppMessages.workflowOnlyFor(AppConstants.Buyer.LULULEMON));
        }
    }

    private String normalizeWeightStatus(String value) {
        if (clean(value) == null) return null;
        return isFailedWeightStatus(value) ? AppConstants.WeightStatus.FAILED : value;
    }

    private void requireOrder(String buyerCode, String orderId) {
        requireLululemonBuyer(buyerCode);
        orderService.getEntity(AppConstants.Buyer.LULULEMON, orderId);
    }

    private PurchaseOrder requirePo(String orderId, String poId) {
        return poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound(AppConstants.Buyer.LULULEMON + " PO")));
    }

    private WeighingOrder requireWeighingOrder(String orderId, String weighingOrderId) {
        WeighingOrder order = weighingOrderRepository.findByIdAndOrderIdAndBuyerCode(weighingOrderId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException(AppMessages.entityNotFound("Weighing Order")));
        assertWeighingOrderAccess(order);
        return order;
    }

    private boolean canAccessWeighingOrder(WeighingOrder order) {
        String scheduleId = clean(order == null ? null : order.getShippingScheduleId());
        if (scheduleId != null) {
            ShippingSchedule schedule = shippingScheduleRepository
                    .findByIdAndOrderIdAndBuyerCode(scheduleId, order.getOrderId(), AppConstants.Buyer.LULULEMON)
                    .orElse(null);
            return schedule != null && factoryAccess.canAccessWeighingFactory(schedule.getFactoryCode());
        }
        // Legacy/non-schedule jobs keep the general LULULEMON optional-Factory behavior.
        return order != null && canAccessFactoryOrUnassigned(order.getFactoryCode());
    }

    private void assertWeighingOrderAccess(WeighingOrder order) {
        if (order == null) return;
        String scheduleId = clean(order.getShippingScheduleId());
        if (scheduleId != null) {
            ShippingSchedule schedule = shippingScheduleRepository
                    .findByIdAndOrderIdAndBuyerCode(scheduleId, order.getOrderId(), AppConstants.Buyer.LULULEMON)
                    .orElseThrow(() -> new IllegalArgumentException("Shipping Schedule not found."));
            factoryAccess.assertWeighingFactoryAccess(schedule.getFactoryCode());
            return;
        }
        assertFactoryAccessIfPresent(order.getFactoryCode());
    }

    private void audit(String action, String resourceId, String description) {
        auditLogService.log(RequestActor.current(), action, AppConstants.AuditResource.WEIGHING, resourceId, description, null, null);
    }

    private static String clean(String value) {
        return TextNormalizer.trimToNull(value);
    }
}
