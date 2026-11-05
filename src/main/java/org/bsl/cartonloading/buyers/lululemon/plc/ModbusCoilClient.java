package org.bsl.cartonloading.buyers.lululemon.plc;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

/** FC01 reads and FC05 writes for ModbusPal simulator ONLY. No direct motor output writes. */
public final class ModbusCoilClient {
    private static final AtomicInteger TX = new AtomicInteger();
    private ModbusCoilClient() { }

    private static byte[] exchange(String host, int port, int unit, byte[] pdu) throws IOException {
        if (unit < 1 || unit > 247 || port != 1502)
            throw new IOException("Only ModbusPal port 1502 / Unit 1–247 is supported");
        int tx = TX.incrementAndGet() & 0xffff;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 2500);
            socket.setSoTimeout(2500);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.writeShort(tx); out.writeShort(0); out.writeShort(pdu.length + 1);
                out.writeByte(unit); out.write(pdu); out.flush();
                int replyTx = in.readUnsignedShort(), protocol = in.readUnsignedShort();
                int length = in.readUnsignedShort(), replyUnit = in.readUnsignedByte();
                if (replyTx != tx || protocol != 0 || replyUnit != unit || length < 3 || length > 253)
                    throw new IOException("Invalid Modbus response header");
                byte[] result = new byte[length - 1]; in.readFully(result);
                if ((result[0] & 0x80) != 0)
                    throw new IOException("Modbus exception code " + (result.length > 1 ? result[1] & 255 : -1));
                if (result[0] != pdu[0]) throw new IOException("Modbus response function mismatch");
                return result;
            }
        }
    }

    public static boolean read(String host, int port, int unit, int address) throws IOException {
        if (address < 0 || address > 65535) throw new IOException("Coil address out of range");
        try {
            byte[] r = exchange(host, port, unit,
                new byte[] {1, (byte)(address >>> 8), (byte) address, 0, 1});
            if (r.length != 3 || r[1] != 1) throw new IOException("Unexpected Modbus coil payload");
            return (r[2] & 1) != 0;
        } catch (IOException error) {
            throw ModbusReadException.from(error, "Conveyor");
        }
    }

    public static void writeCommand(String host, int port, int unit, int address, boolean on) throws IOException {
        if (address < 0 || address > 65535) throw new IOException("Coil address out of range");
        byte[] pdu = new byte[] {5, (byte)(address >>> 8), (byte) address,
            (byte)(on ? 0xff : 0), 0};
        byte[] reply = exchange(host, port, unit, pdu);
        if (!java.util.Arrays.equals(pdu, reply)) throw new IOException("Modbus command acknowledgment mismatch");
    }
}
