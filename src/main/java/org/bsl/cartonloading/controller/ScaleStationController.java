package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.service.facade.ScaleStationFacade;

import org.bsl.cartonloading.common.constants.AppConstants;

import org.bsl.cartonloading.buyers.es.dto.carton.ScaleStationRequest;
import org.bsl.cartonloading.buyers.es.dto.carton.StationHeartbeatRequest;
import org.bsl.cartonloading.buyers.es.model.ScaleStation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping(AppConstants.ApiPath.BARCODE_WORKFLOW_SCALE_STATIONS)
@PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_ACCESS)
public class ScaleStationController {
    private final ScaleStationFacade service;

    public ScaleStationController(ScaleStationFacade service) {
        this.service = service;
    }

    @GetMapping
    public List<ScaleStation> list(@RequestParam(defaultValue = AppConstants.RequestDefault.TRUE) boolean activeOnly) {
        return service.forBuyer(null, impl -> impl.list(activeOnly));
    }

    @GetMapping("/{stationCode}")
    public ScaleStation get(@PathVariable String stationCode) {
        return service.forBuyer(null, impl -> impl.get(stationCode));
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_ADMIN)
    @PostMapping
    public ScaleStation create(@RequestBody ScaleStationRequest request) {
        return service.forBuyer(null, impl -> impl.create(request));
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_ADMIN)
    @PutMapping("/{stationCode}")
    public ScaleStation update(@PathVariable String stationCode, @RequestBody ScaleStationRequest request) {
        return service.forBuyer(null, impl -> impl.update(stationCode, request));
    }

    @PreAuthorize(AppConstants.SecurityExpression.BARCODE_BUYER_SALES_OR_WEIGHT)
    @PostMapping("/{stationCode}/heartbeat")
    public ScaleStation heartbeat(@PathVariable String stationCode, @RequestBody StationHeartbeatRequest request) {
        return service.forBuyer(null, impl -> impl.heartbeat(stationCode, request));
    }
}
