package org.bsl.cartonloading.buyers.lululemon.service;

import org.bsl.cartonloading.buyers.es.model.PackingOrder;
import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;
import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.model.WeighingResult;
import org.bsl.cartonloading.buyers.lululemon.repository.RfidScanSessionRepository;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.model.CartonProcessingHistory;
import org.bsl.cartonloading.service.CartonProcessingHistoryStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

/** Buyer-specific adapter: maps finalized RFID/Weight workflow to the shared history store. */
@Service
public class LululemonHistoryProjectionService {
    private final CartonProcessingHistoryStore store;
    private final RfidScanSessionRepository sessions;
    private final MongoTemplate mongo;
    public LululemonHistoryProjectionService(CartonProcessingHistoryStore store,
            RfidScanSessionRepository sessions, MongoTemplate mongo) {
        this.store = store; this.sessions = sessions; this.mongo = mongo;
    }

    public void recordCompleted(Carton carton, PurchaseOrder po, WeighingResult weight) {
        String rfid = carton.getRfidStatus();
        String weighing = carton.getWeightStatus();
        if (!("PASS".equals(rfid) || "FAILED".equals(rfid))
                || !("PASS".equals(weighing) || "FAILED".equals(weighing))
                || carton.getWeighedAt() == null) return;
        if (weight == null || weight.getId() == null
                || !carton.getId().equals(weight.getCartonId())
                || !carton.getOrderId().equals(weight.getOrderId())
                || !AppConstants.Buyer.LULULEMON.equals(weight.getBuyerCode())
                || !weighing.equals(weight.getResult()))
            throw new IllegalStateException("Saved Weight event does not match the completed Carton");
        if (!AppConstants.Buyer.LULULEMON.equals(carton.getBuyerCode()))
            throw new IllegalArgumentException("Mismatched buyer for History snapshot");
        if (!po.getId().equals(carton.getPoId()) || !po.getOrderId().equals(carton.getOrderId())
                || po.getFactoryCode() == null || carton.getFactoryCode() == null
                || !po.getFactoryCode().equalsIgnoreCase(carton.getFactoryCode()))
            throw new IllegalArgumentException("History PO/Carton ownership mismatch");
        PackingOrder order = mongo.findById(carton.getOrderId(), PackingOrder.class);
        if (order == null || !AppConstants.Buyer.LULULEMON.equals(order.getBuyerCode()))
            throw new IllegalArgumentException("History Order ownership mismatch");
        RfidScanSession session = carton.getRfidSessionId() == null ? null
                : sessions.findById(carton.getRfidSessionId()).orElse(null);
        if (session == null || !carton.getId().equals(session.getCartonId())
                || !AppConstants.Buyer.LULULEMON.equals(session.getBuyerCode())
                || !rfid.equals(session.getStatus()) || session.getFinishedAt() == null)
            throw new IllegalStateException("Completed RFID session missing for History projection");
        CartonProcessingHistory row = new CartonProcessingHistory();
        row.setBuyerCode(carton.getBuyerCode());
        row.setBuyerKey(carton.getBuyerCode());
        row.setOrderId(carton.getOrderId());
        row.setOrderName(order.getOrderName());
        row.setPoId(po.getId());
        row.setCartonId(carton.getId());
        row.setCartonNo(carton.getCartonNo());
        row.setCartonStatus(carton.getStatus());
        row.setFactoryCode(carton.getFactoryCode());
        row.setMasterPo(po.getMasterPo());
        row.setPoNumber(po.getPoNumber());
        row.setPackingPlan(po.getPackingPlan());
        row.setSalesOrderPts(po.getSalesOrderPts());
        row.setSscc18(carton.getSscc18());
        row.setSku(po.getSku());
        row.setPlannedQty(carton.getPlannedQty());
        row.setRfidStatus(rfid);
        row.setWeightStatus(weighing);
        row.setStatus("PASS".equals(rfid) && "PASS".equals(weighing) ? "PASS" : "FAILED");
        String scanner = session.getFinishedBy();
        if (scanner == null || scanner.isBlank()) scanner = session.getStartedBy();
        if (scanner == null || scanner.isBlank()) scanner = carton.getSsccAssignedBy();
        if (scanner == null || scanner.isBlank()) scanner = carton.getQtyConfirmedBy();
        row.setScannedBy(scanner);
        row.setScanTime(session.getFinishedAt());
        row.setWeighedBy(carton.getWeighedBy());
        row.setWeightTime(carton.getWeighedAt());
        row.setUpdatedAt(LocalDateTime.now());
        row.setRfidSessionId(session.getId());
        row.setWeightEventId(weight == null ? null : weight.getId());
        store.save(row);
    }

    public void removeCurrent(Carton carton) {
        store.removeCurrent(carton.getBuyerCode(), carton.getId());
    }
}
