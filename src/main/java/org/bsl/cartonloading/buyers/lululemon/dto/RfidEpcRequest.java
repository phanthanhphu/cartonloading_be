package org.bsl.cartonloading.buyers.lululemon.dto;

import java.util.List;

/** Reader bridge can batch multiple reads; duplicates are deliberately accepted but not counted twice. */
/** Optional importId makes PDA XLSX batches safe to retry after a lost response. */
public record RfidEpcRequest(String sessionId, List<String> epcs, String importId) { }
