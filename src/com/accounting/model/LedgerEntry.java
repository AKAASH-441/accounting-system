package com.accounting.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One row of the tamper-proof ledger. Immutable on purpose: a ledger entry never changes.
 * Sign rule for original entries: EXPENSE amounts are negative, all others positive.
 * A reversal has reversalOf set and the exactly opposite amount.
 */
public class LedgerEntry {
    private final Long id;               // null until saved
    private final LocalDate entryDate;
    private final EntryType entryType;
    private final String category;
    private final String description;
    private final long amountCents;
    private final Long customerId;       // nullable
    private final Long invoiceId;        // nullable
    private final Long reversalOf;       // nullable: id of the entry this one reverses
    private final LocalDateTime createdAt;
    private final String prevHash;       // 64 hex chars
    private final String entryHash;      // 64 hex chars

    public LedgerEntry(Long id, LocalDate entryDate, EntryType entryType, String category,
                       String description, long amountCents, Long customerId, Long invoiceId,
                       Long reversalOf, LocalDateTime createdAt, String prevHash, String entryHash) {
        this.id = id;
        this.entryDate = entryDate;
        this.entryType = entryType;
        this.category = category;
        this.description = description;
        this.amountCents = amountCents;
        this.customerId = customerId;
        this.invoiceId = invoiceId;
        this.reversalOf = reversalOf;
        this.createdAt = createdAt;
        this.prevHash = prevHash;
        this.entryHash = entryHash;
    }

    public Long getId()                 { return id; }
    public LocalDate getEntryDate()     { return entryDate; }
    public EntryType getEntryType()     { return entryType; }
    public String getCategory()         { return category; }
    public String getDescription()      { return description; }
    public long getAmountCents()        { return amountCents; }
    public Long getCustomerId()         { return customerId; }
    public Long getInvoiceId()          { return invoiceId; }
    public Long getReversalOf()         { return reversalOf; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public String getPrevHash()         { return prevHash; }
    public String getEntryHash()        { return entryHash; }

    public boolean isReversal()         { return reversalOf != null; }

    @Override
    public String toString() {
        return "Ledger#" + id + " " + entryType + " " + amountCents + (isReversal() ? " (reverses #" + reversalOf + ")" : "");
    }
}
