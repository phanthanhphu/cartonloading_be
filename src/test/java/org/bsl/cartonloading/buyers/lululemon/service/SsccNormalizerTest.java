package org.bsl.cartonloading.buyers.lululemon.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SsccNormalizerTest {
    private static final String SSCC = "006285770433441817";

    @Test
    void supportsScannerGs1Ai00AndKeepsLeadingZeroes() {
        assertEquals(SSCC, SsccNormalizer.normalize("00006285770433441817"));
        assertEquals(SSCC, SsccNormalizer.normalize(SSCC));
        assertEquals(SSCC, SsccNormalizer.normalize("(00)" + SSCC));
        assertEquals(SSCC, SsccNormalizer.normalize("]C100" + SSCC));
        assertEquals(SSCC, SsccNormalizer.normalize("]C1(00)" + SSCC));
        assertEquals(SSCC, SsccNormalizer.normalize("  00" + SSCC + "\n"));
    }

    @Test
    void rejectsMalformedOrUnexpectedAiWithoutGuessingCarton() {
        assertNull(SsccNormalizer.normalize(null));
        assertNull(SsccNormalizer.normalize(""));
        assertNull(SsccNormalizer.normalize("123456"));
        assertNull(SsccNormalizer.normalize("01" + SSCC));
        assertNull(SsccNormalizer.normalize("000" + SSCC));
    }
}