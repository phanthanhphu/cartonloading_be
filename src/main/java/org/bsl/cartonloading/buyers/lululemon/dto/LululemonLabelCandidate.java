package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

public record LululemonLabelCandidate(LululemonPo po, LululemonCarton carton) { }
