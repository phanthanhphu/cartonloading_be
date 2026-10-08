package org.bsl.cartonloading.buyers.lululemon.dto;

/**
 * Carton identity lookup driven by the physical carton label.
 * PO is required because imported logical POs may not have a SKU assigned yet.
 */
public record LabelLookupRequest(String poNumber, String sku) { }
