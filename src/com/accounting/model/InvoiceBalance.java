package com.accounting.model;

import java.time.LocalDate;

/** One row of the v_invoice_balance view: what an invoice billed, what was paid, what is still due. */
public class InvoiceBalance {
    private final long invoiceId;
    private final String invoiceNo;
    private final long customerId;
    private final LocalDate issueDate;
    private final LocalDate dueDate;
    private final long billedCents;
    private final long paidCents;
    private final long outstandingCents;
    private final int daysOverdue;

    public InvoiceBalance(long invoiceId, String invoiceNo, long customerId, LocalDate issueDate,
                          LocalDate dueDate, long billedCents, long paidCents,
                          long outstandingCents, int daysOverdue) {
        this.invoiceId = invoiceId;
        this.invoiceNo = invoiceNo;
        this.customerId = customerId;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
        this.billedCents = billedCents;
        this.paidCents = paidCents;
        this.outstandingCents = outstandingCents;
        this.daysOverdue = daysOverdue;
    }

    public long getInvoiceId()        { return invoiceId; }
    public String getInvoiceNo()      { return invoiceNo; }
    public long getCustomerId()       { return customerId; }
    public LocalDate getIssueDate()   { return issueDate; }
    public LocalDate getDueDate()     { return dueDate; }
    public long getBilledCents()      { return billedCents; }
    public long getPaidCents()        { return paidCents; }
    public long getOutstandingCents() { return outstandingCents; }
    public int getDaysOverdue()       { return daysOverdue; }

    @Override
    public String toString() {
        return invoiceNo + " outstanding=" + outstandingCents + " overdue=" + daysOverdue + "d";
    }
}
