package org.bsl.cartonloading.buyers.lululemon.service;

import java.math.BigInteger;
import java.util.Locale;

/** Lululemon TrueVUE Vue Item: 12-bit 0x3BD header, 50-bit product, 34-bit serial. */
public final class TrueVueDecoder {
    private static final BigInteger PRODUCT_MASK = BigInteger.ONE.shiftLeft(50).subtract(BigInteger.ONE);
    private static final BigInteger SERIAL_MASK = BigInteger.ONE.shiftLeft(34).subtract(BigInteger.ONE);

    private TrueVueDecoder() {}

    public record Result(String epc, String pc, String productCode, String serialNumber, String vendorCode) {}

    public static Result decode(String input) {
        if (input == null) throw new IllegalArgumentException("RFID EPC is required.");
        String raw = input.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        String pc = null;
        if (raw.matches("[0-9A-F]{28}")) {
            // The PM86/RF88 inventory demo often prepends the 16-bit PC word.
            pc = raw.substring(0, 4);
            int words = (Integer.parseInt(pc, 16) >>> 11) & 0x1F;
            if (words != 6) throw new IllegalArgumentException("RFID PC does not indicate a 96-bit EPC.");
            raw = raw.substring(4);
        }
        if (!raw.matches("[0-9A-F]{24}")) {
            throw new IllegalArgumentException("TrueVUE EPC must be exactly 24 hexadecimal characters (or 28 with PC).");
        }
        BigInteger value = new BigInteger(raw, 16);
        if (value.shiftRight(84).intValue() != 0x3BD) {
            throw new IllegalArgumentException("Unsupported EPC header: expected TrueVUE 3BD.");
        }
        String product = value.shiftRight(34).and(PRODUCT_MASK).toString();
        String serial = value.and(SERIAL_MASK).toString();
        if (serial.length() < 2) throw new IllegalArgumentException("TrueVUE serial has no vendor code.");
        return new Result(raw, pc, product, serial, serial.substring(0, 2));
    }

    /** Numeric leading zeros on UPC/SKU labels are not part of the 50-bit product integer. */
    public static String skuKey(String sku) {
        if (sku == null) return null;
        String value = sku.trim();
        if (value.isEmpty()) return null;
        if (value.matches("[0-9]+")) return new BigInteger(value, 10).toString();
        return value.toUpperCase(Locale.ROOT);
    }
}
