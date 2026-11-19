package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.model.RfidScanSession;
import org.bsl.cartonloading.buyers.lululemon.service.CartonProcessingService;
import org.bsl.cartonloading.buyers.lululemon.service.facade.CartonProcessingFacade;
import org.bsl.cartonloading.buyers.lululemon.service.facade.LululemonRfidFacade;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/** UAT UI hooks, registered only with CARTON_UAT_ENABLED=true. Actual DB usage also requires a separate opt-in. */
@RestController
@ConditionalOnProperty(name = "carton.uat.enabled", havingValue = "true")
@RequestMapping("/api/buyers/{buyer}/lululemon/carton-processing/uat")
@PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
public class UatCartonTestController {
    private final LululemonRfidFacade rfid;
    private final CartonProcessingFacade processing;
    private final boolean liveDatabase;
    private final boolean allowed;
    private final boolean uiTestControlsVisible;

    public UatCartonTestController(LululemonRfidFacade rfid, CartonProcessingFacade processing,
            @Value("${spring.data.mongodb.database:}") String database,
            @Value("${carton.uat.allow-live-database:false}") boolean allowLiveDatabase) {
        // Explicit two-flag opt-in for a live database; never enabled by default.
        boolean dedicatedTestDatabase = database != null
                && database.matches("(?i)^.*(?:[_-](?:uat|test|sandbox))(?:$|[_-].*)");
        this.liveDatabase = !dedicatedTestDatabase;
        this.allowed = dedicatedTestDatabase || allowLiveDatabase;
        // Manual PASS/FAILED simulation UI is for an isolated UAT database only.
        // When both UAT flags are true, never advertise test buttons to the normal UI.
        this.uiTestControlsVisible = dedicatedTestDatabase && !allowLiveDatabase;
        this.rfid = rfid;
        this.processing = processing;
    }

    private void requireAllowed() {
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "UAT on the current database is locked. Set CARTON_UAT_ALLOW_LIVE_DATABASE=true explicitly, "
                    + "or switch to an isolated *_uat database.");
        }
    }

    public record RfidSelection(String orderId, String poId, String sscc, String stationCode, String result, Boolean acknowledgeLiveWrite) { }
    public record WeightSelection(String sscc, BigDecimal actualWeightKg, String result, Boolean acknowledgeLiveWrite) { }

    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String buyer) {
        processing.forBuyer(buyer, impl -> true);
        return Map.of(
                "enabled", allowed,
                "liveDatabase", liveDatabase,
                "uiTestControlsVisible", uiTestControlsVisible,
                "reason", allowed ? "" : "Live database requires explicit CARTON_UAT_ALLOW_LIVE_DATABASE=true.");
    }

    private void requireAcknowledgement(Boolean accepted) {
        requireAllowed();
        if (liveDatabase && !Boolean.TRUE.equals(accepted)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Confirm that simulated RFID/Weight will alter actual Cartons, EPCs and History.");
        }
    }

    @PostMapping("/rfid")
    public RfidScanSession rfid(@PathVariable String buyer, @RequestBody RfidSelection request) {
        if (request == null) throw new IllegalArgumentException("UAT RFID request required.");
        requireAcknowledgement(request.acknowledgeLiveWrite());
        return rfid.forBuyer(buyer, impl -> impl.simulateUat(request.orderId(), request.poId(),
                request.sscc(), request.stationCode(), request.result()));
    }

    @PostMapping("/weight")
    public CartonProcessingService.WeightResult weight(@PathVariable String buyer,
            @RequestBody WeightSelection request) {
        if (request == null) throw new IllegalArgumentException("UAT Weight request required.");
        requireAcknowledgement(request.acknowledgeLiveWrite());
        return processing.forBuyer(buyer, impl -> impl.simulateUatWeight(buyer,
                request.sscc(), request.actualWeightKg(), request.result()));
    }
}
