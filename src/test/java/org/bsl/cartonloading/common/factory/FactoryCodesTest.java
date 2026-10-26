package org.bsl.cartonloading.common.factory;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FactoryCodesTest {
    @Test void dynamicCodesAreAccepted() {
        assertTrue(FactoryCodes.isValid("F1"));
        assertTrue(FactoryCodes.isValid("f8"));
        assertTrue(FactoryCodes.isValid(" F100 "));
        assertEquals("F200", FactoryCodes.requireValid(" f200 "));
    }
    @Test void invalidFactoryCodesAreRejected() {
        for (String code : new String[] {"", "F0", "F01", "F-1", "G1", "*", "F1 F2"}) {
            assertFalse(FactoryCodes.isValid(code), code);
            assertThrows(IllegalArgumentException.class, () -> FactoryCodes.requireValid(code));
        }
    }
}
