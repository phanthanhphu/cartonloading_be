package org.bsl.cartonloading.buyers.lululemon.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrueVueDecoderTest {
    @Test void decodesPublishedLululemonExample() {
        TrueVueDecoder.Result actual = TrueVueDecoder.decode("3BD000001D6F34568FA6AE01");
        assertEquals("123456789", actual.productCode());
        assertEquals("11000000001", actual.serialNumber());
        assertEquals("11", actual.vendorCode());
    }
    @Test void decodesPdaPCPlusEpc() {
        TrueVueDecoder.Result actual = TrueVueDecoder.decode("30003BD00000262A056EBC5AC65E");
        assertEquals("3000", actual.pc());
        assertEquals("160072027", actual.productCode());
        assertEquals("11750000222", actual.serialNumber());
        assertEquals("160072027", TrueVueDecoder.skuKey("000160072027"));
        assertEquals("160019156", TrueVueDecoder.decode("30003BD000002626CB52BC5B017E").productCode());
    }
    @Test void rejectsNonTrueVueAndBrokenLength() {
        assertThrows(IllegalArgumentException.class, () -> TrueVueDecoder.decode("3000E280691500005037B74A0991"));
        assertThrows(IllegalArgumentException.class, () -> TrueVueDecoder.decode("3BD123"));
        assertThrows(IllegalArgumentException.class, () -> TrueVueDecoder.decode("foo"));
    }
}
