package com.accounting.model;

/** The four kinds of ledger entry (matches the CHECK constraint in schema.sql). */
public enum EntryType {
    SALE,          // cash sale          : cash in,  revenue
    CREDIT_SALE,   // invoiced sale      : no cash yet, revenue, creates a due
    PAYMENT,       // customer pays a due: cash in
    EXPENSE;       // money spent        : cash out

    /** Does this entry move actual cash? (Used by the Health Score in Step 4.) */
    public boolean affectsCash() { return this != CREDIT_SALE; }

    /** Does this entry count as revenue? */
    public boolean isRevenue() { return this == SALE || this == CREDIT_SALE; }
}
