package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.core.BuyerFactoryAccessService;
import org.bsl.cartonloading.buyers.lululemon.dto.*;
import org.bsl.cartonloading.buyers.lululemon.model.*;
import org.bsl.cartonloading.buyers.lululemon.repository.*;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.common.socket.AppSocketPublisher;
import org.bsl.cartonloading.service.RequestActor;
import org.bsl.cartonloading.service.AuditLogService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/** Direct Factory -> SSCC/SKU -> RFID -> Weight -> next carton workflow.
 * Legacy shipment/PO-picking records are read-only history, never operational gates.
 */
@Service
public class CartonProcessingService {
    private final CartonRepository cartons;
    private final PurchaseOrderRepository pos;
    private final WeighingResultRepository weightResults;
    private final BuyerFactoryAccessService factories;
    private final WorkflowService workflow;
    private final AppSocketPublisher socket;
    private final AuditLogService audit;
    private final LululemonRfidService rfidService;
    private final ScalePlcDeviceService scales;

    public CartonProcessingService(CartonRepository cartons, PurchaseOrderRepository pos,
                                   WeighingResultRepository weightResults, BuyerFactoryAccessService factories,
                                   WorkflowService workflow, AppSocketPublisher socket, AuditLogService audit,
                                   LululemonRfidService rfidService, ScalePlcDeviceService scales) {
        this.cartons = cartons;
        this.pos = pos;
        this.weightResults = weightResults;
        this.factories = factories;
        this.workflow = workflow;
        this.socket = socket;
        this.audit = audit;
        this.rfidService = rfidService;
        this.scales = scales;
    }

    private void requireBuyer(String buyer) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(buyer))
            throw new IllegalArgumentException("This station only supports LULULEMON.");
    }

    private void requireFactory(String factoryCode) {
        if (factoryCode == null || factoryCode.isBlank())
            throw new IllegalArgumentException("Carton has no Factory. Import/assign its Factory first.");
        if (!factories.canAccessAssignedFactory(factoryCode) && !factories.canAccessWeighingFactory(factoryCode))
            throw new AccessDeniedException("Carton belongs to a different Factory.");
    }

    private Carton requireCarton(String buyer, String sscc) {
        requireBuyer(buyer);
        String value = SsccNormalizer.normalize(sscc);
        if (value == null) throw new IllegalArgumentException("Scan a valid SSCC-18.");
        Carton carton = cartons.findBySscc18(value)
                .orElseThrow(() -> new IllegalArgumentException("SSCC not in imported/assigned Packing cartons."));
        if (!AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()))
            throw new IllegalArgumentException("SSCC is not a LULULEMON carton.");
        requireFactory(carton.getFactoryCode());
        return carton;
    }

    private PurchaseOrder requirePo(Carton carton) {
        return pos.findByIdAndOrderIdAndBuyerCode(carton.getPoId(), carton.getOrderId(), AppConstants.Buyer.LULULEMON)
                .orElseThrow(() -> new IllegalArgumentException("Packing PO missing for this SSCC."));
    }

    public record IdentifiedCarton(PurchaseOrder po, Carton carton) { }
    public record StationSummary(String factoryCode, int total, int processed, int passed, int failed,
                                 int remaining, boolean end, List<String> factories) { }
    public record WeightResult(Carton carton, WeighingResult event) { }

    /** Uses an already-imported/assigned SSCC; never searches POs by picking claims. */
    public synchronized IdentifiedCarton identify(String buyer, CartonProcessingIdentityRequest input) {
        requireBuyer(buyer);
        if (input == null) throw new IllegalArgumentException("SSCC and SKU are required.");
        String scannedSscc = SsccNormalizer.normalize(input.sscc());
        if (scannedSscc == null) throw new IllegalArgumentException("Scan a valid SSCC-18.");
        Carton carton = cartons.findBySscc18(scannedSscc).orElse(null);
        if (carton == null) {
            // The supplied All_BP workbook contains PO and carton quantities but no SSCC.
            // On the selected PO screen assign a NEW physical SSCC to the first unassigned
            // planned carton (FIFO). Never auto-assign without an explicit Order+PO context.
            if (input.orderId() == null || input.orderId().isBlank()
                    || input.poId() == null || input.poId().isBlank())
                throw new IllegalArgumentException("Open an Order > PO before assigning a new SSCC.");
            PurchaseOrder selectedPo = pos.findByIdAndOrderIdAndBuyerCode(input.poId(), input.orderId(),
                    AppConstants.Buyer.LULULEMON).orElseThrow(() ->
                    new IllegalArgumentException("Selected Order/PO not found."));
            requireFactory(selectedPo.getFactoryCode());
            if (input.factoryCode() == null || !input.factoryCode().equalsIgnoreCase(selectedPo.getFactoryCode()))
                throw new AccessDeniedException("PO does not belong to the selected Factory.");
            String skuToAssign = TrueVueDecoder.skuKey(input.sku());
            if (skuToAssign == null) throw new IllegalArgumentException("Scan the carton SKU before assigning SSCC.");
            String expected = TrueVueDecoder.skuKey(selectedPo.getSku());
            if (expected != null && !expected.equals(skuToAssign))
                throw new IllegalArgumentException("SKU does not match selected PO; SSCC not assigned.");
            // The operator selects the physical Carton row first. Never allocate by FIFO:
            // the PO may contain cartons with different Planned Qty (e.g. 14 + 1 pcs).
            // A physical Carton row ID is required before assigning an unrecognized SSCC.
            List<Carton> poCartons = workflow.ensureCartonsForPo(buyer, input.orderId(), input.poId());
            if (input.cartonId() == null || input.cartonId().isBlank())
                throw new IllegalArgumentException("Select a Carton row in this PO before assigning a new SSCC.");
            Carton available = poCartons.stream()
                    .filter(c -> input.cartonId().equals(c.getId()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Selected Carton does not belong to this PO."));
            if (available.getSscc18() != null && !available.getSscc18().isBlank())
                throw new IllegalArgumentException("Selected Carton already has a different SSCC. Rescan the SSCC on its label.");
            if (available.getWeightStatus() != null || available.getRfidSessionId() != null)
                throw new IllegalArgumentException("Selected Carton has RFID/Weight history; supervisor review required.");
            carton = workflow.assignSscc(buyer, input.orderId(), available.getId(), new AssignSsccRequest(scannedSscc));
        }
        if (!AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()))
            throw new IllegalArgumentException("SSCC does not belong to LULULEMON.");
        requireFactory(carton.getFactoryCode());
        // A read or rescan must never silently jump from the selected row to
        // another Carton/PO, even when the SSCC is valid for this Buyer.
        if (input.cartonId() != null && !input.cartonId().isBlank()
                && !input.cartonId().equals(carton.getId()))
            throw new IllegalArgumentException("Scanned SSCC is assigned to another Carton. Check the selected row and label.");
        if (input.factoryCode() != null && !input.factoryCode().isBlank()
                && !input.factoryCode().equalsIgnoreCase(carton.getFactoryCode()))
            throw new AccessDeniedException("SSCC does not belong to the selected Factory.");
        PurchaseOrder po = requirePo(carton);
        if (input.orderId() != null && !input.orderId().isBlank()
                && !input.orderId().equals(carton.getOrderId()))
            throw new AccessDeniedException("SSCC does not belong to the selected Order.");
        if (input.poId() != null && !input.poId().isBlank()
                && !input.poId().equals(carton.getPoId()))
            throw new AccessDeniedException("SSCC does not belong to the selected PO.");
        String scannedSku = TrueVueDecoder.skuKey(input.sku());
        if (scannedSku == null) throw new IllegalArgumentException("Scan the carton SKU.");
        String expectedSku = TrueVueDecoder.skuKey(po.getSku());
        String confirmedSku = TrueVueDecoder.skuKey(carton.getLabelConfirmedSku());
        if (expectedSku != null && !expectedSku.equals(scannedSku))
            throw new IllegalArgumentException("Scanned SKU does not match the PO SKU.");
        if (confirmedSku != null && !confirmedSku.equals(scannedSku))
            throw new IllegalArgumentException("Scanned SKU does not match the carton SKU.");
        if (carton.getPlannedQty() == null || carton.getPlannedQty() <= 0)
            throw new IllegalArgumentException("Carton planned Qty is missing.");
        if (AppConstants.WeightStatus.PASS.equals(carton.getWeightStatus())
                && "PASS".equals(carton.getRfidStatus()))
            throw new IllegalArgumentException("Carton already FINAL PASS. Open history instead.");
        if (carton.getWeightStatus() != null)
            throw new IllegalArgumentException("Carton already has a weight result. Supervisor must reopen it before retry.");
        // Reuse the existing, audited confirmation method; Qty is computed by backend.
        if (carton.getLabelConfirmedAt() == null) {
            // Preserve the barcode's leading zeroes for the existing packing-label
            // confirmation logic. TrueVueDecoder.skuKey is only for comparisons;
            // sending its numeric key back as the SKU corrupts 0-prefixed labels.
            workflow.confirmCartonQuantity(buyer, carton.getOrderId(), carton.getId());
            carton = workflow.confirmCartonLabel(buyer, carton.getOrderId(), carton.getId(),
                    new ConfirmLabelRequest(input.sku().trim()));
        }
        po = requirePo(carton); // claim of previously unset PO SKU may have changed it
        return new IdentifiedCarton(po, carton);
    }

    /** Cross-order summary; Factory scope is ALWAYS validated on the backend. */
    public StationSummary summary(String buyer, String factoryCode) {
        return summary(buyer, factoryCode, null, null);
    }

    /** Counts only selected PO cartons when requested from Order > PO; no cross-PO END. */
    public StationSummary summary(String buyer, String factoryCode, String orderId, String poId) {
        requireBuyer(buyer);
        List<String> accessible = factories.isUnrestricted()
                ? pos.findByBuyerCode(AppConstants.Buyer.LULULEMON).stream()
                    .map(PurchaseOrder::getFactoryCode).filter(code -> code != null && !code.isBlank())
                    .distinct().sorted().toList()
                : factories.accessibleFactories();
        String selected = factoryCode == null ? "" : factoryCode.trim().toUpperCase(Locale.ROOT);
        if (selected.isEmpty()) {
            if (accessible.size() == 1) selected = accessible.get(0);
            else return new StationSummary("", 0, 0, 0, 0, 0, false, accessible);
        }
        requireFactory(selected);
        final String factory = selected;
        List<PurchaseOrder> factoryPos = pos.findByBuyerCodeAndFactoryCode(AppConstants.Buyer.LULULEMON, factory);
        int total = 0, processed = 0, passed = 0, failed = 0;
        for (PurchaseOrder po : factoryPos) {
            if (orderId != null && !orderId.isBlank() && !orderId.equals(po.getOrderId())) continue;
            if (poId != null && !poId.isBlank() && !poId.equals(po.getId())) continue;
            for (Carton c : cartons.findByPoIdOrderByCartonNoAsc(po.getId())) {
                // Count still-unassigned cartons as Remaining. Otherwise END is
                // displayed too early after the last *scanned* carton.
                total++;
                boolean rfidFinished = "PASS".equals(c.getRfidStatus()) || "FAILED".equals(c.getRfidStatus());
                boolean weightFinished = AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())
                        || AppConstants.WeightStatus.FAILED.equals(c.getWeightStatus());
                if (rfidFinished && weightFinished) {
                    processed++;
                    if ("PASS".equals(c.getRfidStatus()) && AppConstants.WeightStatus.PASS.equals(c.getWeightStatus())) passed++;
                    else failed++;
                }
            }
        }
        int remaining = Math.max(0, total - processed);
        return new StationSummary(factory, total, processed, passed, failed, remaining, total > 0 && remaining == 0, accessible);
    }

    /** RFID must be finished, but FAILED does not block weighing. Preserve independent results. */
    private static boolean rfidFinished(Carton carton) {
        return "PASS".equals(carton.getRfidStatus()) || "FAILED".equals(carton.getRfidStatus());
    }

    public void armAutoWeight(String buyer, String sscc, String stationCode) {
        Carton carton = requireCarton(buyer, sscc);
        if (!rfidFinished(carton) || carton.getWeightStatus() != null)
            throw new IllegalArgumentException("Finish RFID (PASS or FAILED) before arming the scale");
        scales.armSimulator(stationCode, carton.getSscc18(), carton.getFactoryCode());
    }

    public synchronized WeightResult autoWeight(String buyer, String sscc, String stationCode, String eventId) {
        Carton carton = requireCarton(buyer, sscc);
        if (!rfidFinished(carton)) throw new IllegalArgumentException("Finish RFID (PASS or FAILED) before auto weight");
        if (carton.getWeightStatus() != null) throw new IllegalArgumentException("Weight already saved");
        // Validate gross weight before claiming a one-use simulator event.
        PurchaseOrder po = requirePo(carton);
        if (po.getGrossWeightKg() == null || po.getGrossWeightKg() <= 0)
            throw new IllegalArgumentException("Gross weight missing in ALL_BP");
        // FAILED RFID does not have rfidVerifiedAt. Use this exact persisted
        // attempt's finish time to reject old samples from a previous carton/scan.
        RfidScanSession finished = rfidService.latest(carton.getOrderId(), carton.getPoId(), carton.getSscc18());
        if (finished == null || !Objects.equals(finished.getId(), carton.getRfidSessionId())
                || !Objects.equals(finished.getStatus(), carton.getRfidStatus()) || finished.getFinishedAt() == null)
            throw new IllegalArgumentException("A completed persisted RFID attempt is required before weighing");
        ScalePlcDeviceService.Reading reading = scales.claimSimulator(stationCode, eventId,
                carton.getSscc18(), carton.getFactoryCode(), finished.getFinishedAt());
        try {
            WeightResult saved = captureWeight(buyer,
                    new CartonProcessingWeightRequest(sscc, stationCode, reading.weightKg()), AppConstants.WeightSource.SIMULATOR, eventId);
            scales.releaseSimulator(stationCode, carton.getSscc18());
            return saved;
        } catch (RuntimeException e) {
            scales.undoSimulatorClaim(stationCode, eventId);
            throw e;
        }
    }

    public synchronized WeightResult manualWeight(String buyer, CartonProcessingWeightRequest input) {
        return captureWeight(buyer, input, AppConstants.WeightSource.MANUAL, null);
    }

    private WeightResult captureWeight(String buyer, CartonProcessingWeightRequest input, String source, String eventId) {
        if (input == null) throw new IllegalArgumentException("Weight data is required.");
        Carton carton = requireCarton(buyer, input.sscc());
        PurchaseOrder po = requirePo(carton);
        if (!"PASS".equals(carton.getRfidStatus()) && !"FAILED".equals(carton.getRfidStatus()))
            throw new IllegalArgumentException("Finish RFID scan before weighing this carton.");
        if (carton.getWeightStatus() != null)
            throw new IllegalArgumentException("Weight already saved. Supervisor reopen is required.");
        String station = input.stationCode() == null ? "" : input.stationCode().trim();
        if (station.isEmpty()) throw new IllegalArgumentException("Scale station code is required.");
        if (input.actualWeightKg() == null || input.actualWeightKg().signum() <= 0)
            throw new IllegalArgumentException("Actual weight must be positive.");
        if (po.getGrossWeightKg() == null || po.getGrossWeightKg() <= 0)
            throw new IllegalArgumentException("ALL_BP gross weight (GW) is missing for this PO.");
        BigDecimal gross = BigDecimal.valueOf(po.getGrossWeightKg()).setScale(4, RoundingMode.HALF_UP);
        BigDecimal expected = gross.multiply(BigDecimal.valueOf(carton.getPlannedQty())).setScale(3, RoundingMode.HALF_UP);
        BigDecimal actual = input.actualWeightKg().setScale(3, RoundingMode.HALF_UP);
        BigDecimal difference = actual.subtract(expected).setScale(3, RoundingMode.HALF_UP);
        BigDecimal tolerance = gross.setScale(3, RoundingMode.HALF_UP);
        String result = difference.abs().compareTo(tolerance) <= 0
                ? AppConstants.WeightStatus.PASS : AppConstants.WeightStatus.FAILED;
        LocalDateTime now = LocalDateTime.now();
        carton.setExpectedWeightKg(expected);
        carton.setActualWeightKg(actual);
        carton.setWeightDifferenceKg(difference);
        carton.setWeightToleranceKg(tolerance);
        carton.setWeightStatus(result);
        carton.setWeightStationCode(station);
        carton.setWeighedBy(RequestActor.current());
        carton.setWeighedAt(now);
        carton.setUpdatedAt(now);
        carton.setUpdatedBy(RequestActor.current());
        carton = cartons.save(carton);
        WeighingResult event = new WeighingResult();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(carton.getOrderId());
        event.setPoId(carton.getPoId());
        event.setPoNumber(carton.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setCartonQty(carton.getPlannedQty());
        event.setSscc18(carton.getSscc18());
        event.setAction(AppConstants.WeightAction.WEIGH);
        event.setExpectedWeightKg(expected);
        event.setActualWeightKg(actual);
        event.setDifferenceKg(difference);
        event.setToleranceKg(tolerance);
        event.setResult(result);
        event.setStationCode(station);
        event.setSource(source);
        event.setScaleEventId(eventId);
        event.setStable(true);
        event.setUserId(RequestActor.current());
        event.setCreatedAt(now);
        event = weightResults.save(event);
        audit.log(RequestActor.current(), AppConstants.AuditAction.WEIGHT_CAPTURE,
                AppConstants.AuditResource.WEIGHING, carton.getId(),
                "Direct Carton Processing " + source + " weight (no Shipping Schedule): SSCC " + carton.getSscc18()
                        + ", Factory " + carton.getFactoryCode() + ", station " + station
                        + ", RFID " + carton.getRfidStatus() + ", weight " + result
                        + ", actual " + actual + " kg", null, null);
        socket.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_RESULT_PREFIX + result, carton.getId());
        return new WeightResult(carton, event);
    }
    /** Supervisor exception for a direct station event. Preserves previous results in audit history. */
    public synchronized Carton reopenManualWeight(String buyer, String sscc, String reason) {
        Carton carton = requireCarton(buyer, sscc);
        if (reason == null || reason.trim().length() < 3)
            throw new IllegalArgumentException("Reopen reason must contain at least 3 characters.");
        if (carton.getWeightStatus() == null)
            throw new IllegalArgumentException("No recorded weight to reopen.");
        WeighingResult latestWeight = weightResults.findTop200ByCartonIdOrderByCreatedAtDesc(carton.getId())
                .stream().filter(row -> AppConstants.WeightAction.WEIGH.equals(row.getAction())
                        || AppConstants.WeightAction.OVERRIDE.equals(row.getAction()))
                .findFirst().orElse(null);
        if (latestWeight == null || latestWeight.getShippingScheduleId() != null)
            throw new IllegalArgumentException("This carton belongs to legacy Shipping weight history. Use its legacy review tools.");
        LocalDateTime now = LocalDateTime.now();
        WeighingResult event = new WeighingResult();
        event.setBuyerCode(AppConstants.Buyer.LULULEMON);
        event.setOrderId(carton.getOrderId());
        event.setPoId(carton.getPoId());
        event.setPoNumber(carton.getPoNumber());
        event.setCartonId(carton.getId());
        event.setCartonNo(carton.getCartonNo());
        event.setCartonQty(carton.getPlannedQty());
        event.setSscc18(carton.getSscc18());
        event.setAction(AppConstants.WeightAction.REOPEN);
        event.setExpectedWeightKg(carton.getExpectedWeightKg());
        event.setActualWeightKg(carton.getActualWeightKg());
        event.setDifferenceKg(carton.getWeightDifferenceKg());
        event.setToleranceKg(carton.getWeightToleranceKg());
        event.setStationCode(carton.getWeightStationCode());
        event.setReason(reason.trim());
        event.setUserId(RequestActor.current());
        event.setCreatedAt(now);
        weightResults.save(event);
        carton.setWeightStatus(null);
        carton.setExpectedWeightKg(null);
        carton.setActualWeightKg(null);
        carton.setWeightDifferenceKg(null);
        carton.setWeightToleranceKg(null);
        carton.setWeightStationCode(null);
        carton.setWeighedBy(null);
        carton.setWeighedAt(null);
        carton.setUpdatedBy(RequestActor.current());
        carton.setUpdatedAt(now);
        carton = cartons.save(carton);
        audit.log(RequestActor.current(), AppConstants.AuditAction.REWEIGH_OPEN,
                AppConstants.AuditResource.WEIGHING, carton.getId(),
                "Direct station weight reopened for SSCC " + carton.getSscc18() + ": " + reason.trim(), null, null);
        socket.cartonLoadingChanged(AppConstants.SocketEvent.WEIGHT_REOPENED, carton.getId());
        return carton;
    }

    /**
     * Explicit operator Recall for an already FINAL FAILED direct-station carton.
     * Only this guarded endpoint may reopen both checks for a failed carton. A
     * FINAL PASS carton still requires the separate Admin review procedures.
     * Original RFID sessions/EPC evidence and Weight events remain in history.
     */
    public synchronized IdentifiedCarton recallFailed(String buyer, String orderId, String poId,
                                                       String cartonId, String reason) {
        requireBuyer(buyer);
        if (orderId == null || orderId.isBlank() || poId == null || poId.isBlank()
                || cartonId == null || cartonId.isBlank())
            throw new IllegalArgumentException("Order, PO and Carton are required to recall a failed result.");
        if (reason == null || reason.trim().length() < 5)
            throw new IllegalArgumentException("Enter a recall reason of at least 5 characters.");
        Carton carton = cartons.findByIdAndOrderIdAndBuyerCode(cartonId, orderId,
                AppConstants.Buyer.LULULEMON).orElseThrow(() ->
                new IllegalArgumentException("Carton does not belong to this LULULEMON Order."));
        if (!poId.equals(carton.getPoId()))
            throw new IllegalArgumentException("Carton does not belong to the selected PO.");
        requireFactory(carton.getFactoryCode());
        PurchaseOrder po = requirePo(carton);
        if (po.getFactoryCode() == null || !po.getFactoryCode().equalsIgnoreCase(carton.getFactoryCode()))
            throw new IllegalArgumentException("Carton Factory does not match the imported PO.");
        boolean rfidDone = "PASS".equals(carton.getRfidStatus()) || "FAILED".equals(carton.getRfidStatus());
        boolean weightDone = AppConstants.WeightStatus.PASS.equals(carton.getWeightStatus())
                || AppConstants.WeightStatus.FAILED.equals(carton.getWeightStatus());
        boolean failed = "FAILED".equals(carton.getRfidStatus())
                || AppConstants.WeightStatus.FAILED.equals(carton.getWeightStatus());
        if (!rfidDone || !weightDone || !failed)
            throw new IllegalArgumentException("Recall is allowed only for FINAL FAILED cartons with finished RFID and Weight.");
        if (carton.getSscc18() == null || carton.getSscc18().isBlank())
            throw new IllegalArgumentException("Carton has no saved SSCC.");
        // Validate both historical records before mutating either result, to
        // minimize the chance of a half-reopened carton on a stale/legacy row.
        WeighingResult weight = weightResults.findTop200ByCartonIdOrderByCreatedAtDesc(cartonId)
                .stream().filter(row -> AppConstants.WeightAction.WEIGH.equals(row.getAction())
                        || AppConstants.WeightAction.OVERRIDE.equals(row.getAction()))
                .findFirst().orElse(null);
        if (weight == null || weight.getShippingScheduleId() != null)
            throw new IllegalArgumentException("Only direct-station results can be recalled here.");
        RfidScanSession prior = rfidService.latest(orderId, poId, carton.getSscc18());
        if (prior == null || !("PASS".equals(prior.getStatus()) || "FAILED".equals(prior.getStatus())))
            throw new IllegalArgumentException("RFID record is missing, active or already reopened. Refresh first.");
        String explanation = "FINAL FAILED recall: " + reason.trim();
        // Weight MUST be reopened before the next RFID session can start.
        // Use the existing audited paths; never delete any prior scan/weight event.
        reopenManualWeight(buyer, carton.getSscc18(), explanation);
        rfidService.reopen(orderId, poId, carton.getSscc18(), explanation);
        Carton updated = requireCarton(buyer, carton.getSscc18());
        audit.log(RequestActor.current(), AppConstants.AuditAction.RECALL_FAILED_CARTON,
                AppConstants.AuditResource.WEIGHING, updated.getId(),
                "Recalled FINAL FAILED carton " + updated.getCartonNo() + " (SSCC " + updated.getSscc18()
                        + ") for RFID then Weight retry. Reason: " + reason.trim(), null, null);
        return new IdentifiedCarton(po, updated);
    }

}
