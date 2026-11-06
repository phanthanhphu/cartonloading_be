package org.bsl.cartonloading.buyers.lululemon.plc;

import org.bsl.cartonloading.common.constants.AppConstants;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** Strict read-only Modbus TCP function 03; MBAP request/response validated before decoding. */
public final class ModbusTcpReader {
    private static final AtomicInteger TX_ID = new AtomicInteger();
    private ModbusTcpReader() { }

    public static long read(String host, int port, int unitId, int address, String encoding) throws IOException {
        int words = (AppConstants.Plc.UINT32.equals(encoding) || AppConstants.Plc.INT32.equals(encoding)) ? 2 : 1;
        if (unitId < 0 || unitId > 247 || address < 0 || address + words > 65536 || port < 1 || port > 65535)
            throw new IOException("Invalid Modbus address, unit or port");
        int tx = TX_ID.incrementAndGet() & 0xFFFF;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), AppConstants.Plc.DEFAULT_CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(AppConstants.Plc.DEFAULT_READ_TIMEOUT_MS);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                // Send one complete Modbus TCP ADU in one socket write. Legacy
                // ModbusPal can close a connection when a request is fragmented
                // across several tiny socket writes on Linux. Request bytes and
                // all read-only validation/weight calculations remain unchanged.
                byte[] request = new byte[] {
                    (byte) (tx >>> 8), (byte) tx, 0, 0, 0, 6,
                    (byte) unitId, 3,
                    (byte) (address >>> 8), (byte) address,
                    (byte) (words >>> 8), (byte) words
                };
                out.write(request);
                out.flush();
                int responseTx = in.readUnsignedShort();
                int protocol = in.readUnsignedShort();
                int len = in.readUnsignedShort();
                int responseUnit = in.readUnsignedByte();
                if (responseTx != tx || protocol != 0 || responseUnit != unitId || len < 3 || len > 253)
                    throw new IOException("Invalid Modbus TCP response header");
                int function = in.readUnsignedByte();
                if (function == 0x83) throw new IOException("PLC Modbus exception code " + in.readUnsignedByte());
                if (function != 3) throw new IOException("Unexpected Modbus function: " + function);
                int count = in.readUnsignedByte();
                if (count != words * 2 || len != 3 + count) throw new IOException("Wrong Modbus register count");
                long value = in.readUnsignedShort();
                if (words == 2) value = (value << 16) | (in.readUnsignedShort() & 0xFFFFL);
                if (AppConstants.Plc.INT16.equals(encoding)) return (short) value;
                if (AppConstants.Plc.INT32.equals(encoding)) return (int) value;
                return value;
            }
        } catch (IOException error) {
            throw ModbusReadException.from(error, "Scale PLC");
        }
    }
}
