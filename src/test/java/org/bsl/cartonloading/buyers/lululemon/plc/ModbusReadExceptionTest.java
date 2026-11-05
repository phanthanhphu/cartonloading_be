package org.bsl.cartonloading.buyers.lululemon.plc;

import org.junit.jupiter.api.Test;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class ModbusReadExceptionTest {
    @Test void timeoutIsActionable() {
        assertTrue(ModbusReadException.from(new SocketTimeoutException("Read timed out"), "Conveyor")
                .getMessage().contains("Timeout"));
    }
    @Test void refusedConnectionIsNotGenericIoError() {
        assertTrue(ModbusReadException.from(new ConnectException("Connection refused"), "Scale PLC")
                .getMessage().contains("TCP connection refused"));
    }
    @Test void modbusIllegalAddressDescribesCoilsRegisters() {
        assertTrue(ModbusReadException.from(new IOException("PLC Modbus exception code 2"), "Scale PLC")
                .getMessage().contains("illegal data address"));
    }
    @Test void earlyCloseIndicatesUnitOrMap() {
        assertTrue(ModbusReadException.from(new EOFException(), "Conveyor")
                .getMessage().contains("Unit ID"));
    }
    @Test void unknownReadFailureDoesNotLeakRawMessage() {
        assertFalse(ModbusReadException.from(new IOException("secret internal socket detail"), "Conveyor")
                .getMessage().contains("secret"));
    }
}
