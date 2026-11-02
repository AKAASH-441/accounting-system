package com.accounting.util;

import java.math.BigDecimal;
import java.util.Locale;

/** Converts between display amounts ("1,234.56") and stored minor units (123456). */
public final class Money {
    private Money() { }

    /** 123456 -> "1,234.56" ; -50000 -> "-500.00" */
    public static String format(long cents) {
        return String.format(Locale.US, "%,.2f", BigDecimal.valueOf(cents, 2));
    }

    /** "1,234.56" -> 123456. Rejects blanks, junk, and more than 2 decimal places. */
    public static long parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("Amount is required");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(text.trim().replace(",", ""));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a valid amount: " + text);
        }
        if (v.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Amount cannot have more than 2 decimal places: " + text);
        }
        return v.movePointRight(2).setScale(0).longValueExact();
    }
}
