package org.bsl.cartonloading.controller;
import org.bsl.cartonloading.buyers.lululemon.dto.SimulateRequest;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ScalePlcOperationsFacade;
import org.bsl.cartonloading.buyers.lululemon.service.LululemonDeviceConnectionLifecycle;
import org.bsl.cartonloading.buyers.lululemon.service.ScalePlcDeviceService;
import org.bsl.cartonloading.common.socket.LululemonDeviceStatusNotifier;
import org.bsl.cartonloading.buyers.lululemon.service.facade.ScalePlcDeviceFacade;

import org.bsl.cartonloading.buyers.lululemon.model.ScalePlcDevice;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.List;

/** Configures/read-only tests LULULEMON scales; never submits a production carton weight. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/scale-plc-devices")
public class ScalePlcDeviceController {
    private final ScalePlcOperationsFacade actions;
    public ScalePlcDeviceController(ScalePlcOperationsFacade actions) { this.actions = actions; }

    @GetMapping
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<ScalePlcDevice> list(@PathVariable String buyer) {
        return actions.forBuyer(buyer, impl -> impl.list(buyer));
    }

    @PostMapping("/test-connection")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDeviceService.PlcConnectionCheck testSerialConnection(
            @PathVariable String buyer,
            @RequestBody ScalePlcDeviceService.Settings settings) {
        return actions.forBuyer(buyer, impl -> impl.testSerialConnection(buyer, settings));
    }

    @PostMapping("/test")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDeviceService.Reading test(
            @PathVariable String buyer,
            @RequestBody ScalePlcDeviceService.Settings settings) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.test(buyer, settings));
    }

    @GetMapping("/mine")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_WEIGHT_CHECK)
    public List<ScalePlcDeviceService.AssignedStation> mine(@PathVariable String buyer) {
        return actions.forBuyer(buyer, impl -> impl.mine(buyer));
    }

    @PutMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDevice save(@PathVariable String buyer, @PathVariable String code,
            @RequestBody ScalePlcDeviceService.Settings settings) {
        return actions.forBuyer(buyer, impl -> impl.save(buyer, code, settings));
    }

    @DeleteMapping("/{code}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public void delete(@PathVariable String buyer, @PathVariable String code) {
        actions.forBuyer(buyer, impl -> { impl.delete(buyer, code); return null; });
    }

    @PostMapping("/{code}/simulate")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public ScalePlcDeviceService.Reading simulate(@PathVariable String buyer, @PathVariable String code,
            @RequestBody SimulateRequest request) {
        return actions.forBuyer(buyer, impl -> impl.simulate(buyer, code, request));
    }

    @GetMapping("/{code}/read")
    @PreAuthorize("@accessControl.canAccessBuyer(#buyer) and (@accessControl.canWeightCheck() or @accessControl.isAdmin())")
    public ScalePlcDeviceService.Reading read(@PathVariable String buyer, @PathVariable String code) throws IOException {
        return actions.forBuyer(buyer, impl -> impl.read(buyer, code));
    }

}
