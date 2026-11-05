package org.bsl.cartonloading.buyers.lululemon.plc;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Safe, actionable error descriptions for read-only Modbus checks; never claim a live connection. */
public final class ModbusReadException extends IOException {
    private static final Pattern MODBUS_CODE = Pattern.compile("(?i)(?:Modbus exception code|PLC Modbus exception code)\\s+(\\d+)");

    private ModbusReadException(String message, IOException cause) { super(message, cause); }

    public static ModbusReadException from(IOException error, String device) {
        if (error instanceof ModbusReadException known) return known;
        String message = error.getMessage() == null ? "" : error.getMessage();
        Matcher code = MODBUS_CODE.matcher(message);
        String reason;
        if (error instanceof SocketTimeoutException) {
            reason = "Timeout waiting for the Modbus device. Check IP, TCP Port, Unit ID and simulator Run status.";
        } else if (error instanceof ConnectException) {
            reason = "TCP connection refused or failed. Check the ModbusPal Run button, IP and TCP Port.";
        } else if (error instanceof NoRouteToHostException) {
            reason = "Host is unreachable. Check the PLC network and IP address.";
        } else if (error instanceof UnknownHostException) {
            reason = "PLC hostname could not be resolved. Check PLC IP / Host.";
        } else if (error instanceof SocketException && message.toLowerCase().contains("reset")) {
            reason = "Connection reset by the Modbus device. Check Unit ID, simulator Run status and connection stability.";
        } else if (error instanceof EOFException) {
            reason = "Connection closed before a full Modbus reply. Check Unit ID and Coil / Holding Register configuration.";
        } else if (code.find()) {
            int number = Integer.parseInt(code.group(1));
            reason = switch (number) {
                case 1 -> "Modbus exception 1 (illegal function): this device does not support the requested read.";
                case 2 -> "Modbus exception 2 (illegal data address): check the configured Coil / Holding Register address.";
                case 3 -> "Modbus exception 3 (illegal data value): check the requested read parameters.";
                case 4 -> "Modbus exception 4 (device failure): check the ModbusPal slave.";
                case 10 -> "Modbus gateway path unavailable: check network routing and Unit ID.";
                case 11 -> "Modbus gateway target did not respond: check Unit ID and slave status.";
                default -> "Modbus device returned exception code " + number + ". Check Unit ID and register mapping.";
            };
        } else if (message.contains("Invalid PLC stable flag")) {
            reason = "Scale stable Holding Register must contain 0 or 1.";
        } else if (message.contains("response") || message.contains("payload") || message.contains("register count")) {
            reason = "Unexpected Modbus response. Verify Unit ID, slave configuration and Coil / Holding Register mapping.";
        } else {
            reason = "Modbus read failed. Check IP, TCP Port, Unit ID and Coil / Holding Register mapping.";
        }
        return new ModbusReadException(device + ": " + reason, error);
    }
}
