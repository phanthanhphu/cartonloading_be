package org.bsl.cartonloading.buyers.lululemon.plc;

import com.fazecast.jSerialComm.SerialPort;
import org.bsl.cartonloading.common.constants.AppConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-only Modbus ASCII (function 03) via Delta DVP COM1 RS232.
 * PLC Dn uses holding register address 0x1000+n (do not enter the 4xxxx notation).
 * An explicit server-side allowlist avoids opening other host serial peripherals.
 * Never writes or changes PLC RUN/STOP, D registers, M bits or any physical output.
 */
public final class ModbusAsciiSerialReader {
    private static final int D_REGISTER_BASE = 0x1000;
    private static final int MAX_D_REGISTER = 9999;
    private static final int MAX_RESPONSE = 96;
    private static final ConcurrentHashMap<String, Object> PORT_LOCKS = new ConcurrentHashMap<>();

    private ModbusAsciiSerialReader() { }

    public static void validateSettings(String comPort, int baud, int bits, String parity, int stops,
                                        int station, int register, String encoding) {
        if (comPort == null || !(comPort.toUpperCase(Locale.ROOT).matches("COM[1-9][0-9]{0,2}")
                || comPort.matches("/dev/tty(?:USB|ACM|S)[0-9]{1,3}")))
            throw new IllegalArgumentException("Serial port must be COM8 (Windows) or /dev/ttyUSB0 (Linux) on the device host (backend or Factory Local Agent)");
        if (!Set.of(4800, 9600, 19200, 38400, 57600, 115200).contains(baud))
            throw new IllegalArgumentException("Unsupported serial baud rate");
        if (bits != 7 && bits != 8) throw new IllegalArgumentException("Serial data bits must be 7 or 8");
        if (parity == null || !Set.of("EVEN", "ODD", "NONE").contains(parity))
            throw new IllegalArgumentException("Serial parity must be EVEN, ODD or NONE");
        if (stops != 1 && stops != 2) throw new IllegalArgumentException("Serial stop bits must be 1 or 2");
        if (station < 1 || station > 247) throw new IllegalArgumentException("Invalid Serial Station Address (1–247)");
        if (encoding == null || !Set.of(AppConstants.Plc.UINT16, AppConstants.Plc.INT16,
                    AppConstants.Plc.UINT32, AppConstants.Plc.INT32).contains(encoding))
            throw new IllegalArgumentException("Invalid serial register encoding");
        int words = words(encoding);
        if (register < 0 || register + words - 1 > MAX_D_REGISTER)
            throw new IllegalArgumentException("Delta D register must be D0 to D9999 (including 32-bit width)");
    }

    public static long read(String comPort, int baud, int bits, String parity, int stops,
                            int station, int dRegister, String encoding) throws IOException {
        validateSettings(comPort, baud, bits, parity, stops, station, dRegister, encoding);
        String normalized = comPort.startsWith("/dev/") ? comPort : comPort.toUpperCase(Locale.ROOT);
        String allowed = System.getenv("CARTON_SCALE_SERIAL_ALLOWED_PORTS");
        if (allowed == null || java.util.Arrays.stream(allowed.split(","))
                .map(String::trim).noneMatch(p -> normalized.equals(p) || (!normalized.startsWith("/dev/") && normalized.equalsIgnoreCase(p))))
            throw new IOException("Serial port " + normalized + " is not allowed on the backend. Set CARTON_SCALE_SERIAL_ALLOWED_PORTS=" + normalized + " and restart the backend.");

        synchronized (PORT_LOCKS.computeIfAbsent(normalized, key -> new Object())) {
            SerialPort serial = SerialPort.getCommPort(normalized);
            int stopFlag = stops == 1 ? SerialPort.ONE_STOP_BIT : SerialPort.TWO_STOP_BITS;
            int parityFlag = switch (parity) {
                case "EVEN" -> SerialPort.EVEN_PARITY;
                case "ODD" -> SerialPort.ODD_PARITY;
                default -> SerialPort.NO_PARITY;
            };
            if (!serial.setComPortParameters(baud, bits, stopFlag, parityFlag))
                throw new IOException("Unable to configure serial parameters for " + normalized);
            serial.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 1500, 1500);
            if (!serial.openPort()) throw new IOException("Cannot open " + normalized + " on backend host (cable, permissions, or WPLSoft is using it)");
            try {
                byte[] req = request(station, D_REGISTER_BASE + dRegister, words(encoding));
                if (serial.writeBytes(req, req.length) != req.length)
                    throw new IOException("Unable to write Modbus ASCII read-only request to " + normalized);
                byte[] one = new byte[1];
                StringBuilder line = new StringBuilder();
                // Bound both read duration (native serial timeout) and total response length.
                while (line.length() < MAX_RESPONSE) {
                    int count = serial.readBytes(one, 1);
                    if (count <= 0) throw new IOException("Modbus ASCII timeout/no reply from Delta PLC on " + normalized);
                    char c = (char) (one[0] & 0xFF);
                    if (c == '\n') return decodeResponse(line.toString(), station, encoding);
                    line.append(c);
                }
                throw new IOException("Modbus ASCII reply exceeded maximum permitted length");
            } finally {
                serial.closePort();
            }
        }
    }

    static byte[] request(int station, int registerAddress, int words) {
        byte[] pdu = { (byte) station, 3, (byte) (registerAddress >>> 8), (byte) registerAddress,
                       0, (byte) words };
        return frame(pdu);
    }

    static byte[] frame(byte[] payload) {
        byte[] bytes = new byte[payload.length + 1];
        System.arraycopy(payload, 0, bytes, 0, payload.length);
        bytes[payload.length] = lrc(payload);
        StringBuilder text = new StringBuilder(":");
        for (byte b : bytes) text.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return (text + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    static byte lrc(byte[] bytes) {
        int sum = 0;
        for (byte b : bytes) sum = (sum + (b & 0xff)) & 0xff;
        return (byte) (-sum);
    }

    static long decodeResponse(String line, int station, String encoding) throws IOException {
        // CR is retained by caller until LF, as required by Modbus ASCII framing.
        if (!line.startsWith(":") || !line.endsWith("\r")) throw new IOException("Invalid ASCII frame delimiters");
        String hex = line.substring(1, line.length() - 1);
        if ((hex.length() & 1) != 0 || hex.length() < 8 || hex.length() > 88)
            throw new IOException("Invalid Modbus ASCII frame length");
        byte[] all = new byte[hex.length() / 2];
        try {
            for (int i = 0; i < all.length; i++) all[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid hex in Modbus ASCII frame", e);
        }
        byte[] body = java.util.Arrays.copyOf(all, all.length - 1);
        if (lrc(body) != all[all.length - 1]) throw new IOException("Modbus ASCII LRC error");
        if ((body[0] & 0xff) != station) throw new IOException("Unexpected Modbus station in reply");
        int function = body[1] & 0xff;
        if (function == 0x83) {
            if (body.length != 3) throw new IOException("Invalid Modbus exception response length");
            throw new IOException("PLC Modbus exception code " + (body[2] & 0xff));
        }
        if (function != 3) throw new IOException("Unexpected Modbus function code " + function);
        int expectedBytes = words(encoding) * 2;
        if ((body[2] & 0xff) != expectedBytes || body.length != 3 + expectedBytes)
            throw new IOException("Unexpected Modbus register count");
        long value = 0;
        for (int i = 3; i < body.length; i++) value = (value << 8) | (body[i] & 0xffL);
        if (AppConstants.Plc.INT16.equals(encoding)) return (short) value;
        if (AppConstants.Plc.INT32.equals(encoding)) return (int) value;
        return value;
    }

    private static int words(String encoding) {
        return AppConstants.Plc.INT32.equals(encoding) || AppConstants.Plc.UINT32.equals(encoding) ? 2 : 1;
    }
}
