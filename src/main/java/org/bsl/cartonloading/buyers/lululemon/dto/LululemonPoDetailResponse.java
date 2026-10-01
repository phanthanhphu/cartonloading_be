package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.LululemonCarton;
import org.bsl.cartonloading.buyers.lululemon.model.LululemonPo;

import java.util.List;

public record LululemonPoDetailResponse(LululemonPo po, List<LululemonCarton> cartons) { }
