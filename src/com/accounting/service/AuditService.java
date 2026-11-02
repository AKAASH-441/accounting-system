package com.accounting.service;

import com.accounting.dao.LedgerDao;
import com.accounting.model.EntryType;
import com.accounting.model.LedgerEntry;
import com.accounting.util.Dates;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Re-checks the whole ledger from the first row and reports exactly where it was altered.
 * Also exports / checks an "anchor": a tiny file recording how long the ledger was and its
 * newest hash. Keep the anchor OUTSIDE this computer (USB stick, email to yourself): the hash
 * chain alone cannot notice entries chopped off the end, the anchor can.
 */
public class AuditService {
    private final LedgerDao ledger;

    public AuditService(Connection conn) { this.ledger = new LedgerDao(conn); }

    public static final class Report {
        private final long entriesChecked;
        private final String tipHash;
        private final List<String> problems;
        private final Long firstBadId;

        Report(long entriesChecked, String tipHash, List<String> problems, Long firstBadId) {
            this.entriesChecked = entriesChecked;
            this.tipHash = tipHash;
            this.problems = Collections.unmodifiableList(problems);
            this.firstBadId = firstBadId;
        }
        public boolean isOk()            { return problems.isEmpty(); }
        public long getEntriesChecked()  { return entriesChecked; }
        public String getTipHash()       { return tipHash; }
        public List<String> getProblems(){ return problems; }
        /** Id of the first row that fails a check, or null if everything is fine. */
        public Long getFirstBadId()      { return firstBadId; }
    }

    public Report verify() {
        List<LedgerEntry> all = ledger.findAll();
        List<String> problems = new ArrayList<>();
        Map<Long, LedgerEntry> seen = new HashMap<>();
        Long firstBad = null;
        String expectedPrev = LedgerHasher.GENESIS_PREV;

        for (LedgerEntry e : all) {
            boolean bad = false;
            String tag = "#" + e.getId() + ": ";

            if (!e.getPrevHash().equals(expectedPrev)) {
                problems.add(tag + "prev_hash does not match the entry before it "
                        + "(an earlier row was changed, removed or inserted)");
                bad = true;
            }
            if (!LedgerHasher.computeFor(e).equals(e.getEntryHash())) {
                problems.add(tag + "entry_hash does not match the row's contents (this row was edited)");
                bad = true;
            }

            if (e.isReversal()) {
                LedgerEntry orig = seen.get(e.getReversalOf());
                if (orig == null) {
                    problems.add(tag + "reverses entry #" + e.getReversalOf() + " which does not exist before it");
                    bad = true;
                } else if (e.getAmountCents() != -orig.getAmountCents()) {
                    problems.add(tag + "reversal amount is not the exact opposite of entry #" + orig.getId());
                    bad = true;
                }
            } else {
                boolean signOk = e.getEntryType() == EntryType.EXPENSE
                        ? e.getAmountCents() < 0 : e.getAmountCents() > 0;
                if (!signOk) {
                    problems.add(tag + "amount has the wrong sign for a " + e.getEntryType() + " entry");
                    bad = true;
                }
            }

            if (bad && firstBad == null) firstBad = e.getId();
            expectedPrev = e.getEntryHash();   // follow the STORED hash so each fault is reported once
            seen.put(e.getId(), e);
        }
        return new Report(all.size(), expectedPrev, problems, firstBad);
    }

    // ---------------- anchor ----------------

    public void exportAnchor(Path file) throws IOException {
        LedgerEntry last = ledger.findLast()
                .orElseThrow(() -> new IllegalStateException("The ledger is empty; nothing to anchor"));
        String text = "ACCOUNTING-LEDGER-ANCHOR v1\n"
                + "entries=" + ledger.count() + "\n"
                + "last_id=" + last.getId() + "\n"
                + "tip_hash=" + last.getEntryHash() + "\n"
                + "exported_at=" + Dates.toDb(LocalDateTime.now().withNano(0)) + "\n";
        Files.writeString(file, text);
    }

    /** Returns a list of problems; empty means the ledger is consistent with the anchor. */
    public List<String> verifyAnchor(Path file) throws IOException {
        Map<String, String> kv = new HashMap<>();
        for (String line : Files.readAllLines(file)) {
            int i = line.indexOf('=');
            if (i > 0) kv.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
        }
        if (!kv.containsKey("entries") || !kv.containsKey("last_id") || !kv.containsKey("tip_hash")) {
            throw new IOException("Not a valid anchor file: " + file);
        }
        long entries = Long.parseLong(kv.get("entries"));
        long lastId  = Long.parseLong(kv.get("last_id"));
        String tip   = kv.get("tip_hash");

        List<String> problems = new ArrayList<>();
        long count = ledger.count();
        if (count < entries) {
            problems.add("Ledger has " + count + " entries but the anchor recorded " + entries
                    + ": entries were deleted after the anchor was exported");
        }
        Optional<LedgerEntry> at = ledger.findById(lastId);
        if (at.isEmpty()) {
            problems.add("Entry #" + lastId + " recorded in the anchor no longer exists");
        } else if (!at.get().getEntryHash().equals(tip)) {
            problems.add("Entry #" + lastId + " is different from the anchor: history was rewritten");
        }
        return problems;
    }
}
