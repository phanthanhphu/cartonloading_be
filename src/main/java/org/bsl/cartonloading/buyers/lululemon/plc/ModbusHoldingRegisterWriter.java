package org.bsl.cartonloading.buyers.lululemon.plc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** FC06 test register writes for an explicitly enabled ModbusPal lab ONLY.
 * Never call from production weight capture or industrial PLC control paths.
 */
public final class ModbusHoldingRegisterWriter {
    private static final AtomicInteger TX = new AtomicInteger();
    private ModbusHoldingRegisterWriter() { }

    public static void write(String host, int port, int unit, int address, int value) throws IOException {
        if (port != 1502 || unit < 1 || unit > 247 || address < 0 || address > 65535
                || value < 0 || value > 65535) throw new IOException("Invalid ModbusPal register write");
        int tx = TX.incrementAndGet() & 0xffff;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(2500);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.writeShort(tx); out.writeShort(0); out.writeShort(6); out.writeByte(unit);
                out.writeByte(6); out.writeShort(address); out.writeShort(value); out.flush();
                int replyTx = in.readUnsignedShort();
                int protocol = in.readUnsignedShort();
                int length = in.readUnsignedShort();
                int replyUnit = in.readUnsignedByte();
                if (tx != replyTx || protocol != 0 || length != 6 || replyUnit != unit)
                    throw new IOException("Invalid ModbusPal FC06 response header");
                int fn = in.readUnsignedByte();
                if (fn == 0x86) throw new IOException("ModbusPal FC06 exception: " + in.readUnsignedByte());
                int returnedAddress = in.readUnsignedShort();
                int returnedValue = in.readUnsignedShort();
                if (fn != 6 || returnedAddress != address || returnedValue != value)
                    throw new IOException("ModbusPal FC06 acknowledgement mismatch");
            }
        }
    }
}
