package org.bsl.cartonloading.buyers.lululemon.controller;

import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;
import java.util.List;

/** Configures/read-only tests LULULEMON scales; never submits a production carton weight. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/scale-plc-devices")
public class ScalePlcDeviceController {
    private final ScalePlcDeviceService service;
    public ScalePlcDeviceController(ScalePlcDeviceService service) { this.service = service; }
    private void buyer(String key) {
        if (!AppConstants.Buyer.LULULEMON.equalsIgnoreCase(key)) throw new IllegalArgumentException("Unsupported buyer");
    }
    @GetMapping
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public List<ScalePlcDevice> list(@PathVariable String buyer) { buyer(buyer); return service.list(); }

    @GetMapping("/mine")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.canWeightCheck()")
    public List<ScalePlcDeviceService.AssignedStation> mine(@PathVariable String buyer) { buyer(buyer); return service.mine(); }

    @PutMapping("/{code}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public ScalePlcDevice save(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ScalePlcDeviceService.Settings settings) { buyer(buyer); return service.save(code, settings); }

    @DeleteMapping("/{code}")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public void delete(@PathVariable String buyer, @PathVariable String code) { buyer(buyer); service.delete(code); }

    public record SimulateRequest(java.math.BigDecimal weightKg) { }

    @PostMapping("/{code}/simulate")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and @accessControl.isAdmin()")
    public ScalePlcDeviceService.Reading simulate(@PathVariable String buyer, @PathVariable String code,
            @RequestBody SimulateRequest request) {
        buyer(buyer);
        return service.simulate(code, request == null ? null : request.weightKg());
    }

    @GetMapping("/{code}/read")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ScalePlcDeviceService.Reading read(@PathVariable String buyer, @PathVariable String code) throws IOException {
        buyer(buyer); return service.read(code);
    }
}
