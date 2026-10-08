package org.bsl.cartonloading.buyers.lululemon.dto;

import org.bsl.cartonloading.buyers.lululemon.model.Carton;
import org.bsl.cartonloading.buyers.lululemon.model.PurchaseOrder;

import java.util.List;

public record PoDetailResponse(PurchaseOrder po, List<Carton> cartons) { }
