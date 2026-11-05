package org.bsl.cartonloading.buyers.lululemon.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RfidScanIssueCountsTest {
    @Test
    void unsupportedTagRepeatedThreeTimesCountsOnceAsWrongSku() {
        List<String> invalid = List.of(
                "40005146 : Invalid TrueVUE header",
                "40005146 : Invalid TrueVUE header",
                "40005146 : Invalid TrueVUE header");
        assertEquals(1, RfidScanIssueCounts.uniqueUnsupportedCount(invalid));
        assertEquals(1, RfidScanIssueCounts.wrongSkuIncludingUnsupported(List.of(), invalid));
        assertEquals(1, RfidScanIssueCounts.withUnsupported(0, invalid));
    }

    @Test
    void twoDifferentUnsupportedTagsAndOneDecodedWrongCountThree() {
        List<String> invalid = List.of("aabb : unsupported", "AABB : invalid", "cd : unsupported");
        assertEquals(2, RfidScanIssueCounts.uniqueUnsupportedCount(invalid));
        assertEquals(3, RfidScanIssueCounts.wrongSkuIncludingUnsupported(List.of("1234"), invalid));
    }

    @Test
    void invalidAndDecodedWrongWithSameTagDoNotDoubleCount() {
        assertEquals(1, RfidScanIssueCounts.wrongSkuIncludingUnsupported(
                List.of("00AB"), List.of("00ab : Invalid", "00AB : Invalid")));
    }

    @Test
    void nullOrEmptyReadsCountZero() {
        assertEquals(0, RfidScanIssueCounts.uniqueUnsupportedCount(null));
        assertEquals(0, RfidScanIssueCounts.wrongSkuIncludingUnsupported(null, null));
    }
}
