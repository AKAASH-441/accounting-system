package com.accounting.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** Immutable, like the database table. Paid/unpaid state lives in InvoiceBalance. */
public class Invoice {
    private final Long id;
    private final String invoiceNo;
    private final long customerId;
    private final LocalDate issueDate;
    private final LocalDate dueDate;
    private final long totalCents;
    private final LocalDateTime createdAt;

    /** A brand-new invoice (id and createdAt are assigned by the database). */
    public Invoice(String invoiceNo, long customerId, LocalDate issueDate, LocalDate dueDate, long totalCents) {
        this(null, invoiceNo, customerId, issueDate, dueDate, totalCents, null);
    }

    public Invoice(Long id, String invoiceNo, long customerId, LocalDate issueDate,
                   LocalDate dueDate, long totalCents, LocalDateTime createdAt) {
        this.id = id;
        this.invoiceNo = invoiceNo;
        this.customerId = customerId;
        this.issueDate = issueDate;
        this.dueDate = dueDate;
        this.totalCents = totalCents;
        this.createdAt = createdAt;
    }

    public Long getId()                 { return id; }
    public String getInvoiceNo()        { return invoiceNo; }
    public long getCustomerId()         { return customerId; }
    public LocalDate getIssueDate()     { return issueDate; }
    public LocalDate getDueDate()       { return dueDate; }
    public long getTotalCents()         { return totalCents; }
    public LocalDateTime getCreatedAt() { return createdAt; }

    @Override
    public String toString() { return "Invoice " + invoiceNo + " (customer " + customerId + ")"; }
}
