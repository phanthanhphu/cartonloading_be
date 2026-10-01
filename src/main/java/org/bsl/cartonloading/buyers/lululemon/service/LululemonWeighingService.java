package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
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
public class LululemonWeighingService {
    private static final String BUYER = BuyerAccess.LULULEMON;
    private static final String CARTON_READY_TO_SHIP = "READY_TO_SHIP";
    private static final String WEIGHT_PASS = "PASS";
    private static final String WEIGHT_HOLD = "HOLD";
    private static final String ORDER_WAITING = "WAITING_FOR_WEIGHING";
    private static final String ORDER_IN_PROGRESS = "IN_PROGRESS";
    private static final String ORDER_HOLD = "HOLD";
    private static final String ORDER_COMPLETED = "COMPLETED";

    private final LululemonPoRepository poRepository;
    private final LululemonCartonRepository cartonRepository;
    private final LululemonWeightProfileRepository profileRepository;
    private final LululemonWeighingOrderRepository weighingOrderRepository;
    private final LululemonWeightEventRepository weightEventRepository;
    private final PackingOrderService orderService;
    private final BuyerFactoryAccessService factoryAccess;
    private final AuditLogService auditLogService;
    private final AppSocketPublisher socketPublisher;

    public LululemonWeighingService(
            LululemonPoRepository poRepository,
            LululemonCartonRepository cartonRepository,
            LululemonWeightProfileRepository profileRepository,
            LululemonWeighingOrderRepository weighingOrderRepository,
            LululemonWeightEventRepository weightEventRepository,
            PackingOrderService orderService,
            BuyerFactoryAccessService factoryAccess,
            AuditLogService auditLogService,
            AppSocketPublisher socketPublisher
    ) {
        this.poRepository = poRepository;
        this.cartonRepository = cartonRepository;
        this.profileRepository = profileRepository;
        this.weighingOrderRepository = weighingOrderRepository;
        this.weightEventRepository = weightEventRepository;
        this.orderService = orderService;
        this.factoryAccess = factoryAccess;
        this.auditLogService = auditLogService;
        this.socketPublisher = socketPublisher;
    }

    public List<LululemonWeighingEligiblePo> eligiblePos(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        List<LululemonWeighingEligiblePo> rows = new ArrayList<>();
        for (LululemonPo po : poRepository.findByOrderIdAndBuyerCodeOrderByPoNumberAsc(orderId, BUYER)) {
            if (!factoryAccess.canAccessFactory(po.getFactoryCode())) continue;
            List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(po.getId());
            if (cartons.isEmpty()) continue;
            boolean ready = cartons.stream().allMatch(c -> CARTON_READY_TO_SHIP.equals(c.getStatus()) && clean(c.getSscc()) != null);
            if (!ready) continue;
            rows.add(new LululemonWeighingEligiblePo(
                    po,
                    profileRepository.findByOrderIdAndPoId(orderId, po.getId()).orElse(null),
                    cartons.size()
            ));
        }
        return rows;
    }

    public LululemonWeightProfile saveProfile(
            String buyerCode, String orderId, String poId, LululemonWeightProfileRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LululemonPo po = requirePo(orderId, poId);
        factoryAccess.assertFactoryAccess(po.getFactoryCode());
        if (request == null) throw new IllegalArgumentException("Weight profile is required.");
        BigDecimal unit = positive(request.unitWeightKg(), "Unit Weight");
        BigDecimal tare = nonNegative(request.tareWeightKg(), "Tare Weight", BigDecimal.ZERO);
        BigDecimal tolerance = nonNegative(request.toleranceKg(), "Tolerance", null);
        if (tolerance == null) throw new IllegalArgumentException("Tolerance is required.");

        LocalDateTime now = LocalDateTime.now();
        LululemonWeightProfile profile = profileRepository.findByOrderIdAndPoId(orderId, poId).orElseGet(LululemonWeightProfile::new);
        if (profile.getId() == null) {
            profile.setBuyerCode(BUYER);
            profile.setOrderId(orderId);
            profile.setPoId(poId);
            profile.setCreatedBy(RequestActor.current());
            profile.setCreatedAt(now);
        }
        profile.setPoNumber(po.getPoNumber());
        profile.setSku(po.getSku());
        profile.setUnitWeightKg(unit.setScale(4, RoundingMode.HALF_UP));
        profile.setTareWeightKg(tare.setScale(4, RoundingMode.HALF_UP));
        profile.setToleranceKg(tolerance.setScale(4, RoundingMode.HALF_UP));
        profile.setUpdatedBy(RequestActor.current());
        profile.setUpdatedAt(now);
        profile = profileRepository.save(profile);
        audit("LULULEMON_WEIGHT_PROFILE_SAVE", poId,
                "Saved weight profile for PO " + po.getPoNumber() + ": unit=" + profile.getUnitWeightKg()
                        + "kg, tare=" + profile.getTareWeightKg() + "kg, tolerance=" + profile.getToleranceKg() + "kg");
        return profile;
    }

    public List<LululemonWeighingOrder> listWeighingOrders(String buyerCode, String orderId) {
        requireOrder(buyerCode, orderId);
        return weighingOrderRepository.findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, BUYER).stream()
                .filter(row -> factoryAccess.canAccessFactory(row.getFactoryCode()))
                .toList();
    }

    public synchronized LululemonWeighingOrder createWeighingOrder(
            String buyerCode, String orderId, LululemonWeighingOrderCreateRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LinkedHashSet<String> poIds = new LinkedHashSet<>(request == null || request.poIds() == null ? List.of() : request.poIds());
        poIds.removeIf(id -> clean(id) == null);
        if (poIds.isEmpty()) throw new IllegalArgumentException("Select at least one completed PO for the Weighing Order.");

        String factory = null;
        LinkedHashSet<String> cartonIds = new LinkedHashSet<>();
        for (String poId : poIds) {
            LululemonPo po = requirePo(orderId, poId);
            factoryAccess.assertFactoryAccess(po.getFactoryCode());
            if (factory == null) factory = po.getFactoryCode();
            else if (!Objects.equals(normalizeFactory(factory), normalizeFactory(po.getFactoryCode()))) {
                throw new IllegalArgumentException("One Weighing Order can contain POs from only one Factory.");
            }
            LululemonWeightProfile profile = profileRepository.findByOrderIdAndPoId(orderId, poId)
                    .orElseThrow(() -> new IllegalArgumentException("Configure Unit Weight / Tare / Tolerance for PO " + po.getPoNumber() + " before creating the Weighing Order."));
            validateProfile(profile, po.getPoNumber());

            List<LululemonCarton> cartons = cartonRepository.findByPoIdOrderByCartonNoAsc(poId);
            if (cartons.isEmpty() || cartons.stream().anyMatch(c -> !CARTON_READY_TO_SHIP.equals(c.getStatus()) || clean(c.getSscc()) == null)) {
                throw new IllegalArgumentException("PO " + po.getPoNumber() + " is not fully Ready to Ship with SSCC assigned for every carton.");
            }
            cartons.forEach(c -> cartonIds.add(c.getId()));
        }

        List<LululemonWeighingOrder> existingOpen = weighingOrderRepository.findByOrderIdAndBuyerCodeOrderByCreatedAtDesc(orderId, BUYER).stream()
                .filter(row -> !ORDER_COMPLETED.equals(row.getStatus()))
                .toList();
        for (LululemonWeighingOrder open : existingOpen) {
            if (open.getCartonIds() == null) continue;
            for (String cartonId : cartonIds) {
                if (open.getCartonIds().contains(cartonId)) {
                    throw new IllegalArgumentException("A selected carton already belongs to open Weighing Order " + open.getName() + ".");
                }
            }
        }

        LocalDateTime now = LocalDateTime.now();
        LululemonWeighingOrder order = new LululemonWeighingOrder();
        order.setBuyerCode(BUYER);
        order.setOrderId(orderId);
        String name = clean(request == null ? null : request.name());
        order.setName(name == null ? "Weighing " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : name);
        order.setFactoryCode(factory);
        order.setPoIds(new ArrayList<>(poIds));
        order.setCartonIds(new ArrayList<>(cartonIds));
        order.setStatus(ORDER_WAITING);
        order.setCreatedBy(RequestActor.current());
        order.setUpdatedBy(RequestActor.current());
        order.setCreatedAt(now);
        order.setUpdatedAt(now);
        order = weighingOrderRepository.save(order);
        audit("LULULEMON_WEIGHING_ORDER_CREATE", order.getId(),
                "Created Weighing Order " + order.getName() + " with " + poIds.size() + " PO(s) / " + cartonIds.size() + " carton(s)");
        socketPublisher.cartonLoadingChanged("LULULEMON_WEIGHING_ORDER_CREATED", order.getId());
        return order;
    }

    public LululemonWeighingLookupResponse lookup(
            String buyerCode, String orderId, String weighingOrderId, LululemonWeighingLookupRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LululemonWeighingOrder weighingOrder = requireWeighingOrder(orderId, weighingOrderId);
        factoryAccess.assertFactoryAccess(weighingOrder.getFactoryCode());
        String sscc = normalizeSscc(request == null ? null : request.sscc());
        if (sscc == null) throw new IllegalArgumentException("Scan a valid 18-digit SSCC.");
        LululemonCarton carton = cartonRepository.findBySscc18(sscc)
                .orElseThrow(() -> new IllegalArgumentException("Unknown SSCC " + sscc + "."));
        validateCartonForOrder(weighingOrder, carton);
        if (clean(carton.getWeightStatus()) != null) {
            throw new IllegalArgumentException("Carton " + carton.getCartonNo() + " already has weighing result "
                    + carton.getWeightStatus() + ". Supervisor/Admin must Re-weigh to open it again.");
        }
        LululemonPo po = requirePo(orderId, carton.getPoId());
        LululemonWeightProfile profile = profileRepository.findByOrderIdAndPoId(orderId, po.getId())
                .orElseThrow(() -> new IllegalArgumentException("Weight profile is missing for PO " + po.getPoNumber() + "."));
        validateProfile(profile, po.getPoNumber());
        BigDecimal expected = expectedWeight(carton, profile);
        return new LululemonWeighingLookupResponse(
                weighingOrder, po, carton, profile, expected,
                "SSCC accepted. Load this carton on the scale and wait for a stable weight."
        );
    }

    public synchronized LululemonWeightSubmitResponse submitWeight(
            String buyerCode, String orderId, String weighingOrderId, LululemonWeightSubmitRequest request
    ) {
        if (request == null) throw new IllegalArgumentException("Weight data is required.");
        if (!Boolean.TRUE.equals(request.stable())) {
            throw new IllegalArgumentException("Scale signal is not Stable. Weight was not accepted.");
        }
        String station = clean(request.stationCode());
        if (station == null) throw new IllegalArgumentException("Station / Device code is required.");
        BigDecimal actual = positive(request.actualWeightKg(), "Actual Weight").setScale(3, RoundingMode.HALF_UP);

        LululemonWeighingLookupResponse lookup = lookup(
                buyerCode, orderId, weighingOrderId, new LululemonWeighingLookupRequest(request.sscc())
        );
        LululemonCarton carton = lookup.carton();
        LululemonPo po = lookup.po();
        LululemonWeightProfile profile = lookup.profile();
        BigDecimal expected = lookup.expectedWeightKg();
        BigDecimal difference = actual.subtract(expected).setScale(3, RoundingMode.HALF_UP);
        BigDecimal tolerance = profile.getToleranceKg().setScale(3, RoundingMode.HALF_UP);
        String result = difference.abs().compareTo(tolerance) <= 0 ? WEIGHT_PASS : WEIGHT_HOLD;
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

        LululemonWeightEvent event = new LululemonWeightEvent();
        event.setBuyerCode(BUYER);
        event.setOrderId(orderId);
        event.setWeighingOrderId(weighingOrderId);
        event.setPoId(po.getId());
        event.setPoNumber(po.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setSscc18(carton.getSscc());
        event.setAction("WEIGH");
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

        LululemonWeighingOrder order = refreshWeighingOrder(requireWeighingOrder(orderId, weighingOrderId));
        audit("LULULEMON_WEIGHT_CAPTURE", carton.getId(),
                "Weighed PO " + po.getPoNumber() + " carton " + carton.getCartonNo() + " SSCC " + carton.getSscc()
                        + ": expected=" + expected + "kg actual=" + actual + "kg result=" + result + " station=" + station);
        socketPublisher.cartonLoadingChanged("LULULEMON_WEIGHT_" + result, carton.getId());
        String message = WEIGHT_PASS.equals(result)
                ? "PASS - carton completed weighing."
                : "WEIGHT MISMATCH / HOLD - carton requires inspection before it can continue.";
        return new LululemonWeightSubmitResponse(order, carton, event, message);
    }

    public synchronized LululemonCarton reopenWeight(
            String buyerCode, String orderId, String weighingOrderId, String cartonId, LululemonExceptionRequest request
    ) {
        requireOrder(buyerCode, orderId);
        LululemonWeighingOrder order = requireWeighingOrder(orderId, weighingOrderId);
        LululemonCarton carton = cartonRepository.findByIdAndOrderIdAndBuyerCode(cartonId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("LULULEMON carton not found."));
        validateCartonForOrder(order, carton);
        String reason = clean(request == null ? null : request.reason());
        if (reason == null || reason.length() < 3) throw new IllegalArgumentException("Reason is required for Re-weigh.");
        if (clean(carton.getWeightStatus()) == null) throw new IllegalArgumentException("This carton has not been weighed yet.");

        LululemonWeightEvent event = new LululemonWeightEvent();
        event.setBuyerCode(BUYER);
        event.setOrderId(orderId);
        event.setWeighingOrderId(weighingOrderId);
        event.setPoId(carton.getPoId());
        event.setPoNumber(carton.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setSscc18(carton.getSscc());
        event.setAction("REOPEN");
        event.setExpectedWeightKg(carton.getExpectedWeightKg());
        event.setActualWeightKg(carton.getActualWeightKg());
        event.setDifferenceKg(carton.getWeightDifferenceKg());
        event.setToleranceKg(carton.getWeightToleranceKg());
        event.setResult("REOPENED");
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
        refreshWeighingOrder(order);
        audit("LULULEMON_REWEIGH_OPEN", cartonId,
                "Re-opened weighing for PO " + carton.getPoNumber() + " carton " + carton.getCartonNo() + ". Reason: " + reason);
        socketPublisher.cartonLoadingChanged("LULULEMON_WEIGHT_REOPENED", cartonId);
        return carton;
    }

    public List<LululemonWeightEvent> history(String buyerCode, String orderId, String weighingOrderId) {
        requireOrder(buyerCode, orderId);
        LululemonWeighingOrder order = requireWeighingOrder(orderId, weighingOrderId);
        factoryAccess.assertFactoryAccess(order.getFactoryCode());
        return weightEventRepository.findByWeighingOrderIdOrderByCreatedAtDesc(weighingOrderId);
    }

    private LululemonWeighingOrder refreshWeighingOrder(LululemonWeighingOrder order) {
        List<LululemonCarton> cartons = new ArrayList<>();
        if (order.getCartonIds() != null) cartonRepository.findAllById(order.getCartonIds()).forEach(cartons::add);
        String status;
        if (!cartons.isEmpty() && cartons.stream().allMatch(c -> WEIGHT_PASS.equals(c.getWeightStatus()))) {
            status = ORDER_COMPLETED;
        } else if (cartons.stream().anyMatch(c -> WEIGHT_HOLD.equals(c.getWeightStatus()))) {
            status = ORDER_HOLD;
        } else if (cartons.stream().anyMatch(c -> clean(c.getWeightStatus()) != null)) {
            status = ORDER_IN_PROGRESS;
        } else {
            status = ORDER_WAITING;
        }
        order.setStatus(status);
        order.setUpdatedBy(RequestActor.current());
        order.setUpdatedAt(LocalDateTime.now());
        return weighingOrderRepository.save(order);
    }

    private void validateCartonForOrder(LululemonWeighingOrder order, LululemonCarton carton) {
        factoryAccess.assertFactoryAccess(carton.getFactoryCode());
        if (!Objects.equals(order.getOrderId(), carton.getOrderId()) || order.getCartonIds() == null || !order.getCartonIds().contains(carton.getId())) {
            throw new IllegalArgumentException("Carton Not in This Weighing Order.");
        }
        if (!CARTON_READY_TO_SHIP.equals(carton.getStatus()) || clean(carton.getSscc()) == null) {
            throw new IllegalArgumentException("Not Ready for Weighing. Carton must finish Carton Loading and have SSCC assigned.");
        }
    }

    private BigDecimal expectedWeight(LululemonCarton carton, LululemonWeightProfile profile) {
        BigDecimal qty = BigDecimal.valueOf(carton.getPlannedQty() == null ? 0 : carton.getPlannedQty());
        return qty.multiply(profile.getUnitWeightKg())
                .add(profile.getTareWeightKg() == null ? BigDecimal.ZERO : profile.getTareWeightKg())
                .setScale(3, RoundingMode.HALF_UP);
    }

    private void validateProfile(LululemonWeightProfile profile, String poNumber) {
        if (profile == null || profile.getUnitWeightKg() == null || profile.getUnitWeightKg().signum() <= 0
                || profile.getToleranceKg() == null || profile.getToleranceKg().signum() < 0) {
            throw new IllegalArgumentException("Weight profile for PO " + poNumber + " is incomplete.");
        }
        if (profile.getTareWeightKg() == null) profile.setTareWeightKg(BigDecimal.ZERO);
    }

    private BigDecimal positive(BigDecimal value, String label) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(label + " must be greater than 0.");
        return value;
    }

    private BigDecimal nonNegative(BigDecimal value, String label, BigDecimal defaultValue) {
        if (value == null) return defaultValue;
        if (value.signum() < 0) throw new IllegalArgumentException(label + " cannot be negative.");
        return value;
    }

    private String normalizeSscc(String value) {
        String v = clean(value);
        if (v == null) return null;
        v = v.replaceAll("[^0-9]", "");
        return v.matches("\\d{18}") ? v : null;
    }

    private String normalizeSource(String value) {
        String v = clean(value);
        if (v == null) return "MANUAL";
        v = v.toUpperCase(Locale.ROOT);
        return Set.of("PLC", "SCALE", "MANUAL").contains(v) ? v : "MANUAL";
    }

    private String normalizeFactory(String value) {
        String v = clean(value);
        return v == null ? null : v.toUpperCase(Locale.ROOT);
    }

    private void requireOrder(String buyerCode, String orderId) {
        String buyer = BuyerAccess.normalize(buyerCode);
        if (!BUYER.equals(buyer)) throw new IllegalArgumentException("This workflow is available only for LULULEMON.");
        orderService.getEntity(BUYER, orderId);
    }

    private LululemonPo requirePo(String orderId, String poId) {
        return poRepository.findByIdAndOrderIdAndBuyerCode(poId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("LULULEMON PO not found."));
    }

    private LululemonWeighingOrder requireWeighingOrder(String orderId, String weighingOrderId) {
        return weighingOrderRepository.findByIdAndOrderIdAndBuyerCode(weighingOrderId, orderId, BUYER)
                .orElseThrow(() -> new IllegalArgumentException("Weighing Order not found."));
    }

    private void audit(String action, String resourceId, String description) {
        auditLogService.log(RequestActor.current(), action, "LULULEMON_WEIGHING", resourceId, description, null, null);
    }

    private static String clean(String value) {
        return TextNormalizer.trimToNull(value);
    }
}
