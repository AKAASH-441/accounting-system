package com.accounting.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** The ONE place that defines how dates and timestamps are stored as text in SQLite. */
public final class Dates {
    private Dates() { }

    /** Timestamp format used in the database (and, in Step 3, inside the hash). */
    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String toDb(LocalDate d)         { return d.toString(); }          // 2026-10-01
    public static String toDb(LocalDateTime t)     { return t.format(TS); }          // 2026-10-01 10:00:00
    public static LocalDate dateFromDb(String s)   { return LocalDate.parse(s); }
    public static LocalDateTime timestampFromDb(String s) { return LocalDateTime.parse(s, TS); }
}
