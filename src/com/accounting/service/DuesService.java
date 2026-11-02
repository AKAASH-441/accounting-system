package com.accounting.service;

import com.accounting.dao.CustomerDao;
import com.accounting.dao.InvoiceDao;
import com.accounting.dao.LedgerDao;
import com.accounting.model.Customer;
import com.accounting.model.EntryType;
import com.accounting.model.Invoice;
import com.accounting.model.LedgerEntry;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Smart Dues Collector. Read-only: everything is derived from the ledger.
 *  - outstanding per invoice = CREDIT_SALE entries minus PAYMENT entries (reversals included)
 *  - reliability per customer = average days late over their (non-reversed) payments
 *  - risk = outstanding x reliability factor x days overdue
 */
public class DuesService {

    public enum Tone {
        NOT_DUE("Not due yet"),
        FRIENDLY("Friendly reminder"),
        FIRM("Firm reminder"),
        FINAL_NOTICE("Final notice");

        private final String label;
        Tone(String label) { this.label = label; }
        public String getLabel() { return label; }
    }

    public static final class Due {
        private final long invoiceId;
        private final String invoiceNo;
        private final long customerId;
        private final String customerName;
        private final long outstandingCents;
        private final long daysOverdue;
        private final Double avgDaysLate;     // null = no payment history yet
        private final double riskScore;
        private final Tone tone;

        Due(long invoiceId, String invoiceNo, long customerId, String customerName,
            long outstandingCents, long daysOverdue, Double avgDaysLate, double riskScore, Tone tone) {
            this.invoiceId = invoiceId;
            this.invoiceNo = invoiceNo;
            this.customerId = customerId;
            this.customerName = customerName;
            this.outstandingCents = outstandingCents;
            this.daysOverdue = daysOverdue;
            this.avgDaysLate = avgDaysLate;
            this.riskScore = riskScore;
            this.tone = tone;
        }
        public long getInvoiceId()        { return invoiceId; }
        public String getInvoiceNo()      { return invoiceNo; }
        public long getCustomerId()       { return customerId; }
        public String getCustomerName()   { return customerName; }
        public long getOutstandingCents() { return outstandingCents; }
        public long getDaysOverdue()      { return daysOverdue; }
        public Double getAvgDaysLate()    { return avgDaysLate; }
        public double getRiskScore()      { return riskScore; }
        public Tone getTone()             { return tone; }
    }

    private final LedgerDao ledger;
    private final InvoiceDao invoices;
    private final CustomerDao customers;

    public DuesService(Connection conn) {
        this.ledger = new LedgerDao(conn);
        this.invoices = new InvoiceDao(conn);
        this.customers = new CustomerDao(conn);
    }

    /** Unpaid invoices, highest risk first. */
    public List<Due> chaseList(LocalDate today) {
        List<LedgerEntry> all = ledger.findAll();

        Set<Long> reversed = new HashSet<>();
        for (LedgerEntry e : all) {
            if (e.isReversal()) reversed.add(e.getReversalOf());
        }

        Map<Long, Long> owed = new LinkedHashMap<>();        // invoice id -> outstanding cents
        Map<Long, double[]> late = new HashMap<>();          // customer id -> {sum of days late, payments}
        Map<Long, Invoice> cache = new HashMap<>();

        for (LedgerEntry e : all) {
            Long invId = e.getInvoiceId();
            if (invId == null) continue;
            if (e.getEntryType() == EntryType.CREDIT_SALE) {
                owed.merge(invId, e.getAmountCents(), Long::sum);
            } else if (e.getEntryType() == EntryType.PAYMENT) {
                owed.merge(invId, -e.getAmountCents(), Long::sum);
                if (!e.isReversal() && !reversed.contains(e.getId())) {
                    Invoice inv = invoiceOf(invId, cache);
                    long days = ChronoUnit.DAYS.between(inv.getDueDate(), e.getEntryDate());
                    double[] a = late.computeIfAbsent(inv.getCustomerId(), k -> new double[2]);
                    a[0] += Math.max(0, days);
                    a[1] += 1;
                }
            }
        }

        List<Due> result = new ArrayList<>();
        for (Map.Entry<Long, Long> en : owed.entrySet()) {
            long outstanding = en.getValue();
            if (outstanding <= 0) continue;
            Invoice inv = invoiceOf(en.getKey(), cache);
            long custId = inv.getCustomerId();
            String name = customers.findById(custId).map(Customer::getName).orElse("Customer #" + custId);
            long daysOverdue = Math.max(0, ChronoUnit.DAYS.between(inv.getDueDate(), today));

            double[] a = late.get(custId);
            Double avg = (a == null || a[1] == 0) ? null : a[0] / a[1];
            double factor = (avg == null) ? 1.5 : 1.0 + avg / 10.0;
            double risk = outstanding * factor * daysOverdue;

            result.add(new Due(en.getKey(), inv.getInvoiceNo(), custId, name,
                    outstanding, daysOverdue, avg, risk, chooseTone(daysOverdue, avg)));
        }
        result.sort((x, y) -> {
            int c = Double.compare(y.getRiskScore(), x.getRiskScore());
            return c != 0 ? c : Long.compare(y.getOutstandingCents(), x.getOutstandingCents());
        });
        return result;
    }

    static Tone chooseTone(long daysOverdue, Double avgDaysLate) {
        if (daysOverdue <= 0) return Tone.NOT_DUE;
        Tone[] ladder = { Tone.FRIENDLY, Tone.FIRM, Tone.FINAL_NOTICE };
        int level = daysOverdue > 15 ? 2 : (daysOverdue > 5 ? 1 : 0);
        if (avgDaysLate != null) {
            if (avgDaysLate <= 3)  level = Math.max(0, level - 1);   // reliable customer: softer
            if (avgDaysLate > 10)  level = Math.min(2, level + 1);   // poor history: harder
        }
        return ladder[level];
    }

    private Invoice invoiceOf(long id, Map<Long, Invoice> cache) {
        Invoice inv = cache.get(id);
        if (inv == null) {
            inv = invoices.findById(id)
                    .orElseThrow(() -> new IllegalStateException("Ledger refers to missing invoice " + id));
            cache.put(id, inv);
        }
        return inv;
    }
}
