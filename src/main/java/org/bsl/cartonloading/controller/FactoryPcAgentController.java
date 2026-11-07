package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.agent.FactoryPcAgentBroker;
import org.bsl.cartonloading.common.constants.AppConstants;
import org.bsl.cartonloading.service.facade.ScalePlcDeviceFacade;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.util.List;

/** Admin-scoped PC enrollment, discovery and revocation. Ports are discovered by the selected PC only. */
@RestController
@RequestMapping("/api/buyers/{buyer}/lululemon/pc-agents")
public class FactoryPcAgentController {
    private final FactoryPcAgentBroker broker;
    private final ScalePlcDeviceFacade facade;
    public FactoryPcAgentController(FactoryPcAgentBroker broker, ScalePlcDeviceFacade facade) {
        this.broker = broker; this.facade = facade;
    }
    private void checkBuyer(String buyer) { facade.forBuyer(buyer, service -> Boolean.TRUE); }
    public record RegisterRequest(String displayName) { }
    public record PortList(String agentId, List<String> ports) { }
    @GetMapping("/{factory}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public List<FactoryPcAgentBroker.PcStatus> list(@PathVariable String buyer, @PathVariable String factory) {
        checkBuyer(buyer); return broker.list(factory);
    }
    @PostMapping("/{factory}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public FactoryPcAgentBroker.Enrollment enroll(@PathVariable String buyer, @PathVariable String factory,
                                                   @RequestBody RegisterRequest request) {
        checkBuyer(buyer); return broker.enroll(factory, request == null ? "" : request.displayName());
    }
    @DeleteMapping("/{factory}/{agentId}")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String buyer, @PathVariable String factory, @PathVariable String agentId) {
        checkBuyer(buyer); broker.revoke(factory, agentId);
    }
    @GetMapping("/{factory}/{agentId}/ports")
    @PreAuthorize(AppConstants.SecurityExpression.BUYER_ADMIN)
    public PortList ports(@PathVariable String buyer, @PathVariable String factory, @PathVariable String agentId) {
        checkBuyer(buyer);
        try { return new PortList(agentId, broker.ports(factory, agentId)); }
        catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Cannot list COM ports: Agent offline, busy or unreachable. Check that it is running.", e); }
    }
}
