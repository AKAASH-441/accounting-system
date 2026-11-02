package com.accounting.service;

import com.accounting.dao.DataAccessException;
import com.accounting.dao.InvoiceDao;
import com.accounting.dao.LedgerDao;
import com.accounting.model.EntryType;
import com.accounting.model.Invoice;
import com.accounting.model.LedgerEntry;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Business rules for writing to the ledger. Every public method is ONE database transaction:
 * read the chain tip, compute the hash, insert. Either everything happens or nothing does.
 * Callers pass POSITIVE amounts; this class applies the sign (EXPENSE is stored negative).
 *
 * Return values: recordCreditSale returns the INVOICE id; every other method returns the LEDGER id.
 */
public class LedgerService {
    private final Connection conn;
    private final LedgerDao ledger;
    private final InvoiceDao invoices;

    public LedgerService(Connection conn) {
        this.conn = conn;
        this.ledger = new LedgerDao(conn);
        this.invoices = new InvoiceDao(conn);
    }

    /** General append. Returns the new ledger id. */
    public long append(EntryType type, LocalDate date, String category, String description,
                       long amountCents, Long customerId, Long invoiceId) {
        if (type == null || date == null) throw new IllegalArgumentException("Type and date are required");
        if (category == null || category.isBlank()) throw new IllegalArgumentException("Category is required");
        if (amountCents <= 0) throw new IllegalArgumentException("Amount must be greater than zero");
        long signed = (type == EntryType.EXPENSE) ? -amountCents : amountCents;
        return inTransaction(() -> appendRaw(type, date, category, description, signed,
                customerId, invoiceId, null));
    }

    public long recordSale(LocalDate date, String category, String description, long amountCents) {
        return append(EntryType.SALE, date, category, description, amountCents, null, null);
    }

    public long recordExpense(LocalDate date, String category, String description, long amountCents) {
        return append(EntryType.EXPENSE, date, category, description, amountCents, null, null);
    }

    /** Creates the invoice AND its CREDIT_SALE ledger entry in one transaction. Returns the invoice id. */
    public long recordCreditSale(String invoiceNo, long customerId, LocalDate issueDate,
                                 LocalDate dueDate, long totalCents) {
        if (totalCents <= 0) throw new IllegalArgumentException("Invoice total must be greater than zero");
        if (dueDate.isBefore(issueDate)) throw new IllegalArgumentException("Due date is before the issue date");
        return inTransaction(() -> {
            long invoiceId = invoices.insert(new Invoice(invoiceNo, customerId, issueDate, dueDate, totalCents));
            appendRaw(EntryType.CREDIT_SALE, issueDate, "Sales", "Invoice " + invoiceNo,
                    totalCents, customerId, invoiceId, null);
            return invoiceId;
        });
    }

    /** A customer pays (part of) an invoice. The database rejects overpayment. Returns the ledger id. */
    public long recordPayment(long invoiceId, LocalDate date, long amountCents) {
        Invoice inv = invoices.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("No such invoice: " + invoiceId));
        return append(EntryType.PAYMENT, date, "Receipts", "Payment for " + inv.getInvoiceNo(),
                amountCents, inv.getCustomerId(), invoiceId);
    }

    /** Fixes a mistake by posting the exact opposite entry. The original is never touched. */
    public long reverse(long entryId, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("A reason is required");
        return inTransaction(() -> {
            LedgerEntry orig = ledger.findById(entryId)
                    .orElseThrow(() -> new IllegalArgumentException("No ledger entry #" + entryId));
            if (orig.isReversal()) {
                throw new IllegalStateException("Entry #" + entryId
                        + " is itself a reversal; a reversal cannot be reversed. Post a new entry instead.");
            }
            LocalDate date = LocalDate.now();
            if (date.isBefore(orig.getEntryDate())) date = orig.getEntryDate();
            return appendRaw(orig.getEntryType(), date, orig.getCategory(),
                    "REVERSAL of #" + entryId + ": " + reason,
                    -orig.getAmountCents(), orig.getCustomerId(), orig.getInvoiceId(), entryId);
        });
    }

    // ---------------------------------------------------------------------------------

    private long appendRaw(EntryType type, LocalDate date, String category, String description,
                           long signedCents, Long customerId, Long invoiceId, Long reversalOf) {
        String desc = description == null ? "" : description;
        String prev = ledger.findLast().map(LedgerEntry::getEntryHash).orElse(LedgerHasher.GENESIS_PREV);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        String hash = LedgerHasher.compute(prev, date, type, category, desc, signedCents,
                customerId, invoiceId, reversalOf, now);
        LedgerEntry e = new LedgerEntry(null, date, type, category, desc, signedCents,
                customerId, invoiceId, reversalOf, now, prev, hash);
        return ledger.insert(e);
    }

    /** Runs work as one transaction. If the caller already opened a transaction, just joins it. */
    private <T> T inTransaction(Supplier<T> work) {
        try {
            if (!conn.getAutoCommit()) return work.get();
            conn.setAutoCommit(false);
            try {
                T result = work.get();
                conn.commit();
                return result;
            } catch (RuntimeException | SQLException ex) {
                try { conn.rollback(); } catch (SQLException ignore) { }
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Transaction failed", e);
        }
    }
}
