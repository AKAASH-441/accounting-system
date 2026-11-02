package com.accounting.service;

import com.accounting.model.EntryType;
import com.accounting.model.LedgerEntry;
import com.accounting.util.Dates;
import com.accounting.util.HashUtil;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Defines exactly which bytes are hashed for a ledger entry.
 * Every field is written as "length:value;" so that no value can ever be confused with
 * its neighbour (a description containing "|" or ";" cannot forge a different entry).
 * The database id is NOT hashed (it is unknown before insert); prev_hash chaining fixes order.
 */
public final class LedgerHasher {
    private LedgerHasher() { }

    /** prev_hash of the very first entry. */
    public static final String GENESIS_PREV = "0".repeat(64);

    public static String compute(String prevHash, LocalDate entryDate, EntryType type,
                                 String category, String description, long amountCents,
                                 Long customerId, Long invoiceId, Long reversalOf,
                                 LocalDateTime createdAt) {
        StringBuilder sb = new StringBuilder("LEDGER-V1;");
        field(sb, prevHash);
        field(sb, Dates.toDb(entryDate));
        field(sb, type.name());
        field(sb, category);
        field(sb, description);
        field(sb, Long.toString(amountCents));
        field(sb, customerId == null ? null : customerId.toString());
        field(sb, invoiceId == null ? null : invoiceId.toString());
        field(sb, reversalOf == null ? null : reversalOf.toString());
        field(sb, Dates.toDb(createdAt));
        return HashUtil.sha256Hex(sb.toString());
    }

    /** Recomputes the hash from a stored row (uses the row's own stored prev_hash). */
    public static String computeFor(LedgerEntry e) {
        return compute(e.getPrevHash(), e.getEntryDate(), e.getEntryType(), e.getCategory(),
                e.getDescription(), e.getAmountCents(), e.getCustomerId(), e.getInvoiceId(),
                e.getReversalOf(), e.getCreatedAt());
    }

    private static void field(StringBuilder sb, String v) {
        if (v == null) {
            sb.append("~;");
        } else {
            sb.append(v.length()).append(':').append(v).append(';');
        }
    }
}
