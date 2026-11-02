package com.accounting.ui;

import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.awt.Component;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Small helpers shared by the screens. */
public final class UiUtil {
    private UiUtil() { }

    /** "1,250.50" -> 125050 cents. */
    public static long parseMoney(String text) {
        try {
            BigDecimal b = new BigDecimal(text.trim().replace(",", ""));
            return b.setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount, for example 1250.50");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Enter an amount with at most 2 decimal places");
        }
    }

    /** Expects yyyy-MM-dd. */
    public static LocalDate parseDate(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Dates must look like 2026-11-02 (year-month-day)");
        }
    }

    public static void error(Component parent, Exception e) {
        String m = e.getMessage();
        JOptionPane.showMessageDialog(parent, (m == null || m.isBlank()) ? e.toString() : m,
                "Cannot complete that action", JOptionPane.ERROR_MESSAGE);
    }

    public static DefaultTableModel readOnlyModel(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
    }
}
