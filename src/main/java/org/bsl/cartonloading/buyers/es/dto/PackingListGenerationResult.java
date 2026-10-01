package org.bsl.cartonloading.buyers.es.dto;

public record PackingListGenerationResult(
        boolean applied,
        int created,
        int skipped,
        String message
) {
}
