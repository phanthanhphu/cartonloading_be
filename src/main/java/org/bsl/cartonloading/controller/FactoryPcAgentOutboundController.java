package org.bsl.cartonloading.controller;

import org.bsl.cartonloading.buyers.lululemon.agent.FactoryPcAgentBroker;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Independently authenticated PC Agent v2 channel. Never accepts PLC write commands. */
@RestController
@RequestMapping("/api/device-agents/v2/scale/{factory}/{agentId}")
public class FactoryPcAgentOutboundController {
    private final FactoryPcAgentBroker broker;
    public FactoryPcAgentOutboundController(FactoryPcAgentBroker broker) { this.broker = broker; }
    @GetMapping("/poll")
    public FactoryPcAgentBroker.Job poll(@PathVariable String factory, @PathVariable String agentId,
            @RequestHeader("X-Scale-Agent-Token") String token,
            @RequestHeader("X-Scale-Agent-Instance-Id") String instanceId) {
        return broker.poll(factory, agentId, token, instanceId);
    }
    @PostMapping("/result")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void result(@PathVariable String factory, @PathVariable String agentId,
            @RequestHeader("X-Scale-Agent-Token") String token,
            @RequestHeader("X-Scale-Agent-Instance-Id") String instanceId,
            @RequestBody FactoryPcAgentBroker.Result result) {
        broker.result(factory, agentId, token, instanceId, result);
    }
}
