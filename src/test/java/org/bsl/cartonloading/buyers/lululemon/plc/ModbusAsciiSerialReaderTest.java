package org.bsl.cartonloading.buyers.lululemon.plc;

import org.bsl.cartonloading.common.constants.AppConstants;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

/** Offline deterministic protocol tests; NEVER opens COM or writes to a PLC. */
class ModbusAsciiSerialReaderTest {
    @Test void deltaD200ShouldMapTo0x10C8() {
        String request = new String(ModbusAsciiSerialReader.request(1, 0x1000 + 200, 1), StandardCharsets.US_ASCII);
        assertTrue(request.startsWith(":010310C80001"));
        assertTrue(request.endsWith("\r\n"));
    }

    @Test void connectionProbeReadsOnlyD0AndDoesNotWriteAnything() {
        // A harmless one-word D0 request uses Function 03 and is independent of scale D/Stable mapping.
        String request = new String(ModbusAsciiSerialReader.request(1, 0x1000, 1), StandardCharsets.US_ASCII);
        assertTrue(request.startsWith(":010310000001"));
        assertTrue(request.endsWith("\r\n"));
    }

    @Test void readSignedAndUnsignedAndValidateLrc() throws Exception {
        // Build responses only through independently verified well-formed Modbus ASCII encoding.
        byte[] body = {1, 3, 2, (byte) 0xFF, (byte) 0xFE};
        String frame = new String(ModbusAsciiSerialReader.frame(body), StandardCharsets.US_ASCII);
        String line = frame.substring(0, frame.length() - 1);
        assertEquals(65534L, ModbusAsciiSerialReader.decodeResponse(line, 1, AppConstants.Plc.UINT16));
        assertEquals(-2L, ModbusAsciiSerialReader.decodeResponse(line, 1, AppConstants.Plc.INT16));
        assertThrows(IOException.class, () -> ModbusAsciiSerialReader.decodeResponse(line, 2, AppConstants.Plc.UINT16));
        String tampered = line.substring(0, line.length() - 3) + "00\r";
        assertThrows(IOException.class, () -> ModbusAsciiSerialReader.decodeResponse(tampered, 1, AppConstants.Plc.UINT16));
    }

    @Test void rejectMalformedFrameAndWrongRegisterCount() {
        assertThrows(IOException.class, () -> ModbusAsciiSerialReader.decodeResponse(":010302FFFF00", 1, AppConstants.Plc.UINT16));
        byte[] exception = {1, (byte) 0x83, 2};
        String line = new String(ModbusAsciiSerialReader.frame(exception), StandardCharsets.US_ASCII);
        assertThrows(IOException.class, () -> ModbusAsciiSerialReader.decodeResponse(line.substring(0, line.length() - 1), 1, AppConstants.Plc.UINT16));
        assertThrows(IllegalArgumentException.class, () -> ModbusAsciiSerialReader.validateSettings("COM8", 9600, 7, "EVEN", 1, 1, 9999, AppConstants.Plc.INT32));
    }
}
