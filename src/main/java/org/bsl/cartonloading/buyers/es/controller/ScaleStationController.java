package org.bsl.cartonloading.buyers.es.controller;

import org.bsl.cartonloading.buyers.es.dto.carton.ScaleStationRequest;
import org.bsl.cartonloading.buyers.es.dto.carton.StationHeartbeatRequest;
import org.bsl.cartonloading.buyers.es.model.ScaleStation;
import org.bsl.cartonloading.buyers.es.service.ScaleStationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/buyers/engelbert-strauss/scale-stations")
@PreAuthorize("@accessControl.canAccessBuyer('ENGELBERT_STRAUSS')")
public class ScaleStationController {
    private final ScaleStationService service;

    public ScaleStationController(ScaleStationService service) {
        this.service = service;
    }

    @GetMapping
    public List<ScaleStation> list(@RequestParam(defaultValue = "true") boolean activeOnly) {
        return service.list(activeOnly);
    }

    @GetMapping("/{stationCode}")
    public ScaleStation get(@PathVariable String stationCode) {
        return service.get(stationCode);
    }

    @PreAuthorize("@accessControl.canAccessBuyer('ENGELBERT_STRAUSS') and (@accessControl.isAdmin())")
    @PostMapping
    public ScaleStation create(@RequestBody ScaleStationRequest request) {
        return service.create(request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer('ENGELBERT_STRAUSS') and (@accessControl.isAdmin())")
    @PutMapping("/{stationCode}")
    public ScaleStation update(@PathVariable String stationCode, @RequestBody ScaleStationRequest request) {
        return service.update(stationCode, request);
    }

    @PreAuthorize("@accessControl.canAccessBuyer('ENGELBERT_STRAUSS') and (@accessControl.canManageSales() or @accessControl.canWeightCheck())")
    @PostMapping("/{stationCode}/heartbeat")
    public ScaleStation heartbeat(@PathVariable String stationCode, @RequestBody StationHeartbeatRequest request) {
        return service.heartbeat(stationCode, request);
    }
}
