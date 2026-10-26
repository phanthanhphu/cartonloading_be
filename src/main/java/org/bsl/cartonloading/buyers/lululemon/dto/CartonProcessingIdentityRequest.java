package org.bsl.cartonloading.buyers.lululemon.dto;

/** Optional Order/PO context is supplied by the active PO page and enforced server-side. */
public record CartonProcessingIdentityRequest(String sscc, String sku, String factoryCode,
                                             String orderId, String poId, String cartonId) { }
