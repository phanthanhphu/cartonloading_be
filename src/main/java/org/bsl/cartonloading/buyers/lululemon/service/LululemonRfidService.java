package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.RfidEpcRequest;
import org.bsl.cartonloading.buyers.lululemon.dto.RfidStartRequest;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.CartonItem;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.CartonItemRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.PurchaseOrderRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidScanSessionRepository;
import org.bsl.cartonloading.buyers.lululemon.repository.ShippingScheduleRepository;
import org.bsl.cartonloading.buyers.lululemon.model.ShippingSchedule;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.RequestActor;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class LululemonRfidService {
    private final RfidScanSessionRepository sessions;
    private final CartonRepository cartons;
    private final CartonItemRepository items;
    private final PurchaseOrderRepository pos;
    private final BuyerFactoryAccessService factoryAccess;
    private final ShippingScheduleRepository shippingSchedules;
    private final ShippingScheduleService shippingScheduleService;
    private final MongoTemplate mongoTemplate;

    public LululemonRfidService(RfidScanSessionRepository sessions, CartonRepository cartons,
                               CartonItemRepository items, PurchaseOrderRepository pos, BuyerFactoryAccessService factoryAccess,
                               ShippingScheduleRepository shippingSchedules, ShippingScheduleService shippingScheduleService,
                               MongoTemplate mongoTemplate) {
        this.sessions = sessions;
        this.cartons = cartons;
        this.items = items;
        this.pos = pos;
        this.factoryAccess = factoryAccess;
        this.shippingSchedules = shippingSchedules;
        this.shippingScheduleService = shippingScheduleService;
        this.mongoTemplate = mongoTemplate;
    }

    private Carton requireCarton(String orderId, String poId, String sscc) {
        String normalized = SsccNormalizer.normalize(sscc);
        if (normalized == null) throw new IllegalArgumentException("Valid SSCC-18 is required.");
        Carton carton = cartons.findBySscc18(normalized)
                .orElseThrow(() -> new IllegalArgumentException("SSCC not found in Lululemon cartons."));
        if (!AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode())
                || !Objects.equals(orderId, carton.getOrderId())
                || !Objects.equals(poId, carton.getPoId())) {
            throw new IllegalArgumentException("SSCC does not belong to the selected Lululemon PO.");
        }
        if (!factoryAccess.canAccessAssignedFactory(carton.getFactoryCode())
                && !factoryAccess.canAccessWeighingFactory(carton.getFactoryCode())) {
            throw new org.springframework.security.access.AccessDeniedException("Factory access denied");
        }
        return carton;
    }

    private PurchaseOrder requirePo(String orderId, String poId) {
        return pos.findByIdAndOrderIdAndBuyerCode(poId, orderId, AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("Lululemon PO not found."));
    }

    /** Factory is loaded from the persisted carton, never trusted from a frontend parameter. */
    public String factoryForCarton(String orderId, String poId, String sscc) {
        return requireCarton(orderId, poId, sscc).getFactoryCode();
    }

    public RfidScanSession latest(String orderId, String poId, String sscc) {
        Carton carton = requireCarton(orderId, poId, sscc);
        return sessions.findTopByCartonIdAndBuyerCodeOrderByStartedAtDesc(carton.getId(), AppConstants.Buyer.LULULEMON)
                .orElse(null);
    }

    public List<RfidScanSession> history(String orderId, String poId, String sscc) {
        Carton carton = requireCarton(orderId, poId, sscc);
        return sessions.findByCartonIdAndBuyerCodeOrderByStartedAtDesc(carton.getId(), AppConstants.Buyer.LULULEMON);
    }

    public synchronized RfidScanSession start(String orderId, String poId, RfidStartRequest request) {
        Carton carton = requireCarton(orderId, poId, request == null ? null : request.sscc());
        PurchaseOrder po = requirePo(orderId, poId);
        // Do not rescan while a saved weight is still authoritative. Admin must
        // first reopen the direct-station weight (audit history is retained).
        if (carton.getWeightStatus() != null)
            throw new IllegalArgumentException("Weight already saved. Admin must reopen weight before another RFID scan.");
        // Factory-scoped carton processing: RFID runs BEFORE weighing.
        // The persisted carton identity, not PO picking or Shipping dispatch, is the gate.
        if (!CartonWeightReadiness.isReady(po, carton)) {
            throw new IllegalArgumentException("Carton needs a confirmed SSCC, planned Qty and SKU identity before RFID scan.");
        }
        String sku = TrueVueDecoder.skuKey(carton.getLabelConfirmedSku());
        String poSku = TrueVueDecoder.skuKey(po.getSku());
        if (sku == null) sku = poSku;
        if (sku == null || (poSku != null && !sku.equals(poSku))) {
            throw new IllegalArgumentException("Carton and PO must have a matching confirmed SKU before RFID scan.");
        }
        int expected = carton.getPlannedQty() == null ? 0 : carton.getPlannedQty();
        if (expected <= 0) throw new IllegalArgumentException("Carton has no planned quantity.");
        RfidScanSession previous = latest(orderId, poId, request.sscc());
        if (previous != null && "SCANNING".equals(previous.getStatus())) {
            String requestingStation = request.stationCode() == null ? "" : request.stationCode().trim();
            String activeStation = previous.getStationCode() == null ? "" : previous.getStationCode();
            if (!activeStation.equalsIgnoreCase(requestingStation)) {
                throw new IllegalArgumentException("This carton is currently being scanned at another station.");
            }
            return previous;
        }
        if (previous != null && "PASS".equals(previous.getStatus())) {
            throw new IllegalArgumentException("Carton already passed RFID. A supervisor must reopen it before rescan.");
        }
        RfidScanSession session = new RfidScanSession();
        session.setBuyerCode(AppConstants.Buyer.LULULEMON);
        session.setOrderId(orderId);
        session.setPoId(poId);
        session.setPoNumber(po.getPoNumber());
        session.setCartonId(carton.getId());
        session.setCartonNo(carton.getCartonNo());
        session.setSscc18(carton.getSscc18());
        session.setStationCode(request.stationCode() == null ? null : request.stationCode().trim());
        session.setExpectedSku(sku);
        session.setExpectedQty(expected);
        session.setStatus("SCANNING");
        session.setStartedBy(RequestActor.current());
        session.setStartedAt(LocalDateTime.now());
        session = sessions.save(session);
        carton.setRfidStatus("SCANNING");
        carton.setRfidVerifiedQty(null);
        carton.setRfidSessionId(session.getId());
        cartons.save(carton);
        refreshRfidReview(orderId, poId);
        return session;
    }

    /**
     * A new physical PDA START must never reuse EPCs imported by an earlier capture.
     * End the old SCANNING attempt as RESTARTED and preserve its evidence in history,
     * then open a clean session. A never-used initial session can safely be reused.
     * The expected session ID prevents stale browser tabs from resetting another scan.
     * Caller must first ensure that no PDA capture still reserves this session.
     */
    public synchronized RfidScanSession freshPdaCapture(String orderId, String poId, String sscc,
                                                        String stationCode, String expectedSessionId) {
        if (expectedSessionId == null || expectedSessionId.isBlank()) {
            throw new IllegalArgumentException("Active RFID sessionId is required for PDA START.");
        }
        RfidScanSession previous = latest(orderId, poId, sscc);
        if (previous == null || !"SCANNING".equals(previous.getStatus())
                || !expectedSessionId.equals(previous.getId())) {
            throw new IllegalArgumentException("RFID session changed. Refresh carton before starting PDA.");
        }
        String requestedStation = stationCode == null ? "" : stationCode.trim();
        if (!requestedStation.equalsIgnoreCase(previous.getStationCode())) {
            throw new IllegalArgumentException("RFID session belongs to another station.");
        }
        // Avoid generating empty history rows when first opening a carton.
        boolean hasOldReads = (previous.getReadCount() != null && previous.getReadCount() > 0)
                || (previous.getEpcs() != null && !previous.getEpcs().isEmpty())
                || (previous.getInvalidReads() != null && !previous.getInvalidReads().isEmpty());
        if (!hasOldReads) return previous;

        // Atomic compare-and-set ensures two connected readers / two backend JVMs
        // cannot both replace the same populated scan attempt.
        RfidScanSession archived = mongoTemplate.findAndModify(
                Query.query(Criteria.where("_id").is(previous.getId()).and("status").is("SCANNING")),
                new Update().set("status", "RESTARTED")
                        .set("reason", "New PDA START: earlier reads archived, not carried into this scan.")
                        .set("finishedBy", RequestActor.current())
                        .set("finishedAt", LocalDateTime.now()),
                FindAndModifyOptions.options().returnNew(true), RfidScanSession.class);
        if (archived == null) {
            throw new IllegalArgumentException("Another RFID capture already restarted this carton. Refresh and retry.");
        }
        // start() allocates fresh EPC/invalid/count collections and sets carton sessionId.
        return start(orderId, poId, new RfidStartRequest(sscc, stationCode));
    }

    /** Called by PDA test upload or future fixed-reader bridge, both through the same API. */
    public synchronized RfidScanSession ingest(String orderId, String poId, String sscc, RfidEpcRequest request) {
        Carton carton = requireCarton(orderId, poId, sscc);
        // New station order is RFID -> Weight; do not require a saved weight here.
        // currentSession() still validates SSCC, session ownership and state.
        RfidScanSession session = currentSession(carton, request == null ? null : request.sessionId());
        if (request == null || request.epcs() == null || request.epcs().isEmpty()) {
            throw new IllegalArgumentException("At least one EPC is required.");
        }
        if (request.epcs().size() > 1000) throw new IllegalArgumentException("Maximum 1000 EPC reads per request.");
        String importId = request.importId() == null ? "" : request.importId().trim();
        if (!importId.isEmpty()) {
            if (!importId.matches("[A-Za-z0-9_.:-]{1,160}"))
                throw new IllegalArgumentException("Invalid RFID batch importId.");
            if (session.getProcessedImportIds() != null && session.getProcessedImportIds().contains(importId))
                return session; // Exact retry must not increment readCount again.
            if (session.getProcessedImportIds() != null && session.getProcessedImportIds().size() >= 500)
                throw new IllegalArgumentException("RFID session exceeded batch import limit.");
        }
        Set<String> known = new LinkedHashSet<>(session.getEpcs());
        for (String raw : request.epcs()) {
            session.setReadCount(session.getReadCount() + 1);
            try {
                TrueVueDecoder.Result decoded = TrueVueDecoder.decode(raw);
                // Never inflate quantity when the reader re-reports an EPC.
                if (known.contains(decoded.epc())) continue;
                if (known.size() >= 5000) throw new IllegalArgumentException("Too many unique EPCs for a carton scan.");
                known.add(decoded.epc());
                if (!session.getExpectedSku().equals(TrueVueDecoder.skuKey(decoded.productCode()))) {
                    session.getWrongSkuEpcs().add(decoded.epc());
                }
                if (sessions.existsVerifiedEpcInOtherCarton(AppConstants.Buyer.LULULEMON, decoded.epc(), carton.getId())) {
                    session.getCrossCartonEpcs().add(decoded.epc());
                }
            } catch (IllegalArgumentException exception) {
                if (session.getInvalidReads().size() < 100) {
                    session.getInvalidReads().add(String.valueOf(raw) + " : " + exception.getMessage());
                }
            }
        }
        session.setEpcs(new ArrayList<>(known));
        session.setActualQty(known.size());
        if (!importId.isEmpty()) {
            if (session.getProcessedImportIds() == null) session.setProcessedImportIds(new ArrayList<>());
            session.getProcessedImportIds().add(importId);
        }
        return sessions.save(session);
    }

    public synchronized RfidScanSession finish(String orderId, String poId, String sscc, String sessionId) {
        Carton carton = requireCarton(orderId, poId, sscc);
        RfidScanSession session = currentSession(carton, sessionId);
        List<String> errors = new ArrayList<>();
        if (!Objects.equals(session.getExpectedQty(), session.getActualQty())) {
            errors.add("RFID quantity " + session.getActualQty() + "/" + session.getExpectedQty());
        }
        if (!session.getWrongSkuEpcs().isEmpty()) errors.add("Wrong SKU: " + session.getWrongSkuEpcs().size());
        if (!session.getCrossCartonEpcs().isEmpty()) errors.add("EPC already in another carton: " + session.getCrossCartonEpcs().size());
        if (!session.getInvalidReads().isEmpty()) errors.add("Invalid/unsupported EPC reads: " + session.getInvalidReads().size());
        List<CartonItem> itemSlots = items.findByCartonIdOrderByItemNoAsc(carton.getId());
        if (itemSlots.size() != session.getExpectedQty()) {
            errors.add("Carton item slots " + itemSlots.size() + "/" + session.getExpectedQty());
        }
        String status = errors.isEmpty() ? "PASS" : "FAILED";
        if ("PASS".equals(status)) {
            // Slots are logical: EPCs are assigned deterministically, NOT claimed to represent
            // any physical scan order or a one-to-one barcode scan event.
            List<String> uniqueSortedEpcs = new ArrayList<>(session.getEpcs());
            uniqueSortedEpcs.sort(String::compareTo);
            for (int i = 0; i < itemSlots.size(); i++) {
                CartonItem slot = itemSlots.get(i);
                TrueVueDecoder.Result decoded = TrueVueDecoder.decode(uniqueSortedEpcs.get(i));
                slot.setRfidStatus(AppConstants.RfidStatus.VERIFIED);
                slot.setRfidEpc(decoded.epc());
                slot.setRfidSerialNumber(decoded.serialNumber());
                slot.setRfidVerifiedAt(LocalDateTime.now());
                slot.setUpdatedAt(LocalDateTime.now());
            }
            items.saveAll(itemSlots);
        }
        session.setStatus(status);
        session.setReason(errors.isEmpty() ? "All EPCs match the carton SKU and expected quantity." : String.join("; ", errors));
        session.setFinishedBy(RequestActor.current());
        session.setFinishedAt(LocalDateTime.now());
        session = sessions.save(session);
        carton.setRfidStatus(status);
        carton.setRfidVerifiedQty(session.getActualQty());
        carton.setRfidSessionId(session.getId());
        carton.setRfidVerifiedAt("PASS".equals(status) ? session.getFinishedAt() : null);
        cartons.save(carton);
        refreshRfidReview(orderId, poId);
        return session;
    }

    /** Explicit admin reopen; do not mutate prior PASS evidence or erase EPC history. */
    public synchronized RfidScanSession reopen(String orderId, String poId, String sscc, String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new IllegalArgumentException("Supervisor reason must be at least five characters.");
        }
        Carton carton = requireCarton(orderId, poId, sscc);
        RfidScanSession previous = latest(orderId, poId, sscc);
        if (previous == null || "SCANNING".equals(previous.getStatus())) {
            throw new IllegalArgumentException("No completed RFID inspection to reopen.");
        }
        RfidScanSession reset = new RfidScanSession();
        reset.setBuyerCode(AppConstants.Buyer.LULULEMON);
        reset.setOrderId(orderId);
        reset.setPoId(poId);
        reset.setCartonId(carton.getId());
        reset.setCartonNo(carton.getCartonNo());
        reset.setSscc18(carton.getSscc18());
        reset.setStatus("REOPENED");
        reset.setReason(reason.trim());
        reset.setStartedBy(RequestActor.current());
        reset.setStartedAt(LocalDateTime.now());
        reset = sessions.save(reset);
        carton.setRfidStatus("WAITING_RFID");
        carton.setRfidSessionId(reset.getId());
        carton.setRfidVerifiedQty(null);
        carton.setRfidVerifiedAt(null);
        cartons.save(carton);
        List<CartonItem> itemSlots = items.findByCartonIdOrderByItemNoAsc(carton.getId());
        for (CartonItem slot : itemSlots) {
            slot.setRfidStatus(AppConstants.RfidStatus.WAITING_RFID);
            slot.setRfidEpc(null);
            slot.setRfidSerialNumber(null);
            slot.setRfidVerifiedAt(null);
        }
        if (!itemSlots.isEmpty()) items.saveAll(itemSlots);
        refreshRfidReview(orderId, poId);
        return reset;
    }

    /** Recompute the independent review state when RFID changes after weight completion. */
    private void refreshRfidReview(String orderId, String poId) {
        PurchaseOrder po = requirePo(orderId, poId);
        String review = RfidReviewStatus.fromCartons(cartons.findByPoIdOrderByCartonNoAsc(poId));
        if (!Objects.equals(po.getRfidReviewStatus(), review)) {
            po.setRfidReviewStatus(review);
            po.setUpdatedAt(LocalDateTime.now());
            pos.save(po);
        }
        for (ShippingSchedule schedule : shippingSchedules
                .findByOrderIdAndBuyerCodeOrderByExFtyDateAscCreatedAtDesc(orderId, AppConstants.Buyer.LULULEMON)) {
            if (schedule.getPoIds() == null || !schedule.getPoIds().contains(poId)) continue;
            List<Carton> allCartons = new ArrayList<>();
            for (String assignedPoId : schedule.getPoIds()) {
                allCartons.addAll(cartons.findByPoIdOrderByCartonNoAsc(assignedPoId));
            }
            String scheduleReview = RfidReviewStatus.fromCartons(allCartons);
            if (!Objects.equals(schedule.getRfidReviewStatus(), scheduleReview)) {
                schedule.setRfidReviewStatus(scheduleReview);
                schedule.setUpdatedAt(LocalDateTime.now());
                shippingSchedules.save(schedule);
            }
            if (!ShippingScheduleService.COMPLETED.equals(schedule.getStatus())) {
                // Covers deployments requiring RFID PASS before Shipping Schedule completion:
                // the schedule can finish automatically once the final RFID exception is fixed.
                shippingScheduleService.refreshCompletion(orderId, schedule.getId());
            }
        }
    }

    private RfidScanSession currentSession(Carton carton, String sessionId) {
        RfidScanSession session = sessions.findTopByCartonIdAndBuyerCodeOrderByStartedAtDesc(carton.getId(), AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("Start RFID scan for this SSCC first."));
        if (sessionId == null || !sessionId.equals(session.getId()) || !"SCANNING".equals(session.getStatus())) {
            throw new IllegalArgumentException("RFID session is stale or already finished. Refresh the carton session.");
        }
        return session;
    }
}
