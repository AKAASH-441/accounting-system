package com.accounting;

import com.accounting.dao.CustomerDao;
import com.accounting.dao.InvoiceDao;
import com.accounting.dao.LedgerDao;
import com.accounting.db.Database;
import com.accounting.model.*;
import com.accounting.util.Money;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Step 2 self-test. Everything runs in one transaction that is rolled back at the end. */
public class Step2Check {

    private static final String ZERO   = "0".repeat(64);
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_C = "c".repeat(64);
    private static final String HASH_F = "f".repeat(64);

    private interface Action { void run() throws Exception; }

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("Database file  : " + Database.getPath());
        try (Connection conn = Database.getConnection()) {
            conn.setAutoCommit(false);          // one transaction for the whole test
            try {
                runChecks(conn);
            } finally {
                conn.rollback();                // leave the database untouched
            }
            check("Rollback left the ledger empty", new LedgerDao(conn).count() == 0);
        }
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void runChecks(Connection conn) throws Exception {
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT sqlite_version()")) {
                rs.next();
                System.out.println("SQLite version : " + rs.getString(1));
            }
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                rs.next();
                check("foreign_keys pragma is ON for this connection", rs.getInt(1) == 1);
            }
        }

        CustomerDao customers = new CustomerDao(conn);
        InvoiceDao  invoices  = new InvoiceDao(conn);
        LedgerDao   ledger    = new LedgerDao(conn);

        // --- Customers and invoices ---
        long custId = customers.insert(new Customer("Ravi Traders", "9876543210", null));
        check("Customer inserted (id=" + custId + ")", custId > 0);
        check("Customer read back by id",
              "Ravi Traders".equals(customers.findById(custId).orElseThrow().getName()));

        long invId = invoices.insert(new Invoice("INV-001", custId,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 500000));
        check("Invoice inserted (id=" + invId + ")", invId > 0);

        // --- Ledger: genesis entry (placeholder hash; real SHA-256 arrives in Step 3) ---
        LedgerEntry genesis = new LedgerEntry(null, LocalDate.of(2026, 10, 1), EntryType.CREDIT_SALE,
                "Sales", "Invoice INV-001", 500000, custId, invId, null,
                LocalDateTime.of(2026, 10, 1, 10, 0, 0), ZERO, HASH_A);
        final long gid = ledger.insert(genesis);
        check("Genesis ledger entry appended (id=" + gid + ")", gid > 0);
        check("Ledger count is 1", ledger.count() == 1);
        check("Chain tip is the entry we just wrote",
              ledger.findLast().orElseThrow().getEntryHash().equals(HASH_A));

        InvoiceBalance bal = invoices.findBalance(invId).orElseThrow();
        System.out.println("        Balance of " + bal.getInvoiceNo() + ": billed "
                + Money.format(bal.getBilledCents()) + ", outstanding "
                + Money.format(bal.getOutstandingCents()) + ", overdue " + bal.getDaysOverdue() + " days");
        check("Outstanding balance is 5,000.00", bal.getOutstandingCents() == 500000);
        check("Outstanding list contains the invoice", invoices.findOutstandingBalances().size() == 1);

        // --- Protections: these MUST be rejected by the database ---
        expectFailure("UPDATE on ledger is blocked by trigger", "IMMUTABLE", () -> {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE ledger SET amount_cents = 1 WHERE id = " + gid);
            }
        });
        expectFailure("DELETE on ledger is blocked by trigger", "IMMUTABLE", () -> {
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("DELETE FROM ledger WHERE id = " + gid);
            }
        });

        LedgerEntry forked = new LedgerEntry(null, LocalDate.of(2026, 10, 21), EntryType.SALE,
                "Sales", "Counter sale", 10000, null, null, null,
                LocalDateTime.of(2026, 10, 21, 9, 0, 0), HASH_F, HASH_C);
        expectFailure("Insert with wrong prev_hash is rejected", "prev_hash", () -> ledger.insert(forked));

        expectFailure("Foreign keys are enforced (invoice for missing customer)", "FOREIGN KEY", () ->
                invoices.insert(new Invoice("INV-X", 9999,
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 1000)));

        check("Ledger still has exactly 1 entry after the rejected attempts", ledger.count() == 1);
    }

    private static void check(String label, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
    }

    /** Runs an action that must throw an exception whose message contains expectedText. */
    private static void expectFailure(String label, String expectedText, Action action) {
        try {
            action.run();
            check(label + "  <-- was NOT rejected!", false);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            check(label, msg.contains(expectedText));
            String shortMsg = msg.length() > 140 ? msg.substring(0, 140) + "..." : msg;
            System.out.println("        DB said: " + shortMsg);
        }
    }
}
