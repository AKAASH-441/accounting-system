package com.accounting;

import com.accounting.dao.CustomerDao;
import com.accounting.dao.InvoiceDao;
import com.accounting.dao.LedgerDao;
import com.accounting.db.Database;
import com.accounting.model.*;
import com.accounting.service.AuditService;
import com.accounting.service.LedgerHasher;
import com.accounting.service.LedgerService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Step 3 test. Works on a throw-away COPY of the database; your real one is never touched. */
public class Step3Check {

    private interface Action { void run() throws Exception; }

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path real   = Paths.get("db", "accounting.db");
        Path tmp    = Paths.get("out", "step3_test.db");
        Path anchor = Paths.get("out", "step3_anchor.txt");
        Files.copy(real, tmp, StandardCopyOption.REPLACE_EXISTING);
        Database.setPath(tmp);
        System.out.println("Throw-away copy: " + Database.getPath());
        try (Connection conn = Database.getConnection()) {
            run(conn, anchor);
        } finally {
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(anchor);
        }
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void run(Connection conn, Path anchor) throws Exception {
        LedgerDao ledger = new LedgerDao(conn);
        LedgerService svc = new LedgerService(conn);
        AuditService audit = new AuditService(conn);

        check("Test copy starts with an empty ledger", ledger.count() == 0);
        check("Audit of an empty ledger passes", audit.verify().isOk());

        // ---------- honest bookkeeping ----------
        long custId = new CustomerDao(conn).insert(new Customer("Ravi Traders", "9876543210", null));
        long invId  = svc.recordCreditSale("INV-001", custId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 15), 500000);
        long payId  = svc.recordPayment(invId, LocalDate.of(2026, 10, 10), 200000);
        long expId  = svc.recordExpense(LocalDate.of(2026, 10, 12), "Supplies", "Packaging material", 150000);
        long revId  = svc.reverse(expId, "Entered twice");

        check("Four entries were appended", ledger.count() == 4);
        List<LedgerEntry> all = ledger.findAll();
        check("First entry links to the genesis hash", all.get(0).getPrevHash().equals(LedgerHasher.GENESIS_PREV));

        boolean linked = true, hexOk = true;
        for (int i = 0; i < all.size(); i++) {
            if (i > 0 && !all.get(i).getPrevHash().equals(all.get(i - 1).getEntryHash())) linked = false;
            if (!all.get(i).getEntryHash().matches("[0-9a-f]{64}")) hexOk = false;
        }
        check("Every entry's prev_hash equals the previous entry's hash", linked);
        check("Every entry_hash is a 64-character SHA-256 hex string", hexOk);

        check("Expense is stored as a negative amount", ledger.findById(expId).orElseThrow().getAmountCents() == -150000);
        LedgerEntry rev = ledger.findById(revId).orElseThrow();
        check("Reversal is the exact opposite and points at the expense",
              rev.getAmountCents() == 150000 && Long.valueOf(expId).equals(rev.getReversalOf()));

        AuditService.Report ok = audit.verify();
        check("Audit passes on the honest ledger (4 entries checked)", ok.isOk() && ok.getEntriesChecked() == 4);
        check("Invoice outstanding is 3,000.00",
              new InvoiceDao(conn).findBalance(invId).orElseThrow().getOutstandingCents() == 300000);

        // ---------- rejected operations must leave nothing behind ----------
        expectFailure("Overpayment is rejected", "outstanding balance",
                () -> svc.recordPayment(invId, LocalDate.of(2026, 10, 20), 999999999L));
        expectFailure("A reversal cannot be reversed", "reversal", () -> svc.reverse(revId, "oops"));
        expectFailure("The same entry cannot be reversed twice", "reversal_of", () -> svc.reverse(expId, "again"));
        check("Ledger still has exactly 4 entries after the rejected attempts", ledger.count() == 4);

        // ---------- anchor ----------
        audit.exportAnchor(anchor);
        check("Anchor file matches the untouched ledger", audit.verifyAnchor(anchor).isEmpty());

        // ---------- the attacker: drops the triggers, then edits the file directly ----------
        List<String> triggers = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'trigger'")) {
            while (rs.next()) triggers.add(rs.getString(1));
        }
        for (String t : triggers) exec(conn, "DROP TRIGGER \"" + t + "\"");
        check("Attacker dropped the " + triggers.size() + " triggers", !triggers.isEmpty());

        LedgerEntry orig = ledger.findById(payId).orElseThrow();

        // Tamper 1: shrink the payment, leave the hash alone
        exec(conn, "UPDATE ledger SET amount_cents = 20000 WHERE id = " + payId);
        AuditService.Report r = audit.verify();
        show(r);
        check("Tamper 1 (edit an amount) is detected", !r.isOk());
        check("Tamper 1 is pinpointed to entry #" + payId, Long.valueOf(payId).equals(r.getFirstBadId()));
        check("Tamper 1 flags only that one entry", r.getProblems().size() == 1);
        exec(conn, "UPDATE ledger SET amount_cents = " + orig.getAmountCents() + " WHERE id = " + payId);
        check("Putting the original value back makes the audit pass again", audit.verify().isOk());

        // Tamper 2: smarter forger also recomputes that row's hash
        LedgerEntry forged = new LedgerEntry(orig.getId(), orig.getEntryDate(), orig.getEntryType(),
                orig.getCategory(), orig.getDescription(), 20000, orig.getCustomerId(), orig.getInvoiceId(),
                orig.getReversalOf(), orig.getCreatedAt(), orig.getPrevHash(), "");
        String newHash = LedgerHasher.computeFor(forged);
        exec(conn, "UPDATE ledger SET amount_cents = 20000, entry_hash = '" + newHash + "' WHERE id = " + payId);
        r = audit.verify();
        show(r);
        check("Tamper 2 (edit amount AND recompute its hash) is detected", !r.isOk());
        check("Tamper 2 breaks the link at the NEXT entry, #" + (payId + 1),
              Long.valueOf(payId + 1).equals(r.getFirstBadId()));
        exec(conn, "UPDATE ledger SET amount_cents = " + orig.getAmountCents()
                + ", entry_hash = '" + orig.getEntryHash() + "' WHERE id = " + payId);
        check("Restoring the original row makes the audit pass again", audit.verify().isOk());

        // Tamper 3: delete the newest entry (the chain alone cannot see this)
        exec(conn, "DELETE FROM ledger WHERE id = " + revId);
        check("Newest entry was deleted by the attacker", ledger.count() == 3);
        check("Tamper 3: the hash chain alone cannot see a deleted tail (expected)", audit.verify().isOk());
        List<String> anchorProblems = audit.verifyAnchor(anchor);
        for (String p : anchorProblems) System.out.println("        ANCHOR: " + p);
        check("Tamper 3: the anchor file exposes the deletion", !anchorProblems.isEmpty());
    }

    private static void show(AuditService.Report r) {
        for (String p : r.getProblems()) System.out.println("        AUDIT: " + p);
    }

    private static void exec(Connection conn, String sql) throws Exception {
        try (Statement st = conn.createStatement()) { st.executeUpdate(sql); }
    }

    private static void check(String label, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
    }

    private static void expectFailure(String label, String expectedText, Action action) {
        try {
            action.run();
            check(label + "  <-- was NOT rejected!", false);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            check(label, msg.contains(expectedText));
            String shortMsg = msg.length() > 140 ? msg.substring(0, 140) + "..." : msg;
            System.out.println("        said: " + shortMsg);
        }
    }
}
