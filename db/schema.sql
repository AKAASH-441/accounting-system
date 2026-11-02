-- =====================================================================
-- Small Business Accounting System : schema + ledger protection
-- Conventions:
--   * Money is stored as INTEGER minor units (paise/cents). Never REAL.
--   * Dates are ISO text 'YYYY-MM-DD'; timestamps 'YYYY-MM-DD HH:MM:SS'.
-- =====================================================================
PRAGMA foreign_keys = ON;

-- 1. CUSTOMERS (editable master data)
CREATE TABLE customers (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL CHECK (length(trim(name)) > 0),
    phone      TEXT,
    email      TEXT,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- 2. INVOICES (immutable; paid/unpaid status is derived from the ledger)
CREATE TABLE invoices (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_no  TEXT    NOT NULL UNIQUE,
    customer_id INTEGER NOT NULL REFERENCES customers(id) ON DELETE RESTRICT,
    issue_date  TEXT    NOT NULL CHECK (date(issue_date) = issue_date),
    due_date    TEXT    NOT NULL CHECK (date(due_date)   = due_date),
    total_cents INTEGER NOT NULL CHECK (total_cents > 0),
    created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
    CHECK (due_date >= issue_date)
);

-- 3. LEDGER (append-only, hash-chained)
--    entry_type: SALE (cash sale), CREDIT_SALE (invoiced), PAYMENT, EXPENSE
--    Sign rule for ORIGINAL entries: EXPENSE negative, all others positive.
--    A REVERSAL is a row with reversal_of = original id and opposite amount.
CREATE TABLE ledger (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_date   TEXT    NOT NULL CHECK (date(entry_date) = entry_date),
    entry_type   TEXT    NOT NULL
                 CHECK (entry_type IN ('SALE','CREDIT_SALE','PAYMENT','EXPENSE')),
    category     TEXT,
    description  TEXT    NOT NULL CHECK (length(trim(description)) > 0),
    amount_cents INTEGER NOT NULL CHECK (amount_cents <> 0),
    customer_id  INTEGER REFERENCES customers(id) ON DELETE RESTRICT,
    invoice_id   INTEGER REFERENCES invoices(id)  ON DELETE RESTRICT,
    reversal_of  INTEGER REFERENCES ledger(id)    ON DELETE RESTRICT,
    created_at   TEXT    NOT NULL,
    prev_hash    TEXT    NOT NULL CHECK (length(prev_hash)  = 64),
    entry_hash   TEXT    NOT NULL UNIQUE CHECK (length(entry_hash) = 64),

    CHECK (reversal_of IS NOT NULL
           OR (entry_type = 'EXPENSE' AND amount_cents < 0)
           OR (entry_type <> 'EXPENSE' AND amount_cents > 0)),

    CHECK (entry_type NOT IN ('CREDIT_SALE','PAYMENT')
           OR (customer_id IS NOT NULL AND invoice_id IS NOT NULL))
);

CREATE UNIQUE INDEX ux_ledger_reversal_of
    ON ledger(reversal_of) WHERE reversal_of IS NOT NULL;
CREATE INDEX ix_ledger_date     ON ledger(entry_date);
CREATE INDEX ix_ledger_customer ON ledger(customer_id);
CREATE INDEX ix_ledger_invoice  ON ledger(invoice_id);
CREATE INDEX ix_invoices_cust   ON invoices(customer_id);

-- TRIGGERS: IMMUTABILITY
CREATE TRIGGER trg_ledger_no_update BEFORE UPDATE ON ledger
BEGIN
    SELECT RAISE(ABORT, 'LEDGER IS IMMUTABLE: updates are not allowed. Post a reversal entry instead.');
END;

CREATE TRIGGER trg_ledger_no_delete BEFORE DELETE ON ledger
BEGIN
    SELECT RAISE(ABORT, 'LEDGER IS IMMUTABLE: deletes are not allowed. Post a reversal entry instead.');
END;

CREATE TRIGGER trg_invoices_no_update BEFORE UPDATE ON invoices
BEGIN
    SELECT RAISE(ABORT, 'INVOICES ARE IMMUTABLE: cancel by reversing the credit sale in the ledger.');
END;

CREATE TRIGGER trg_invoices_no_delete BEFORE DELETE ON invoices
BEGIN
    SELECT RAISE(ABORT, 'INVOICES ARE IMMUTABLE: deletes are not allowed.');
END;

-- TRIGGERS: CHAIN INTEGRITY ON INSERT
CREATE TRIGGER trg_ledger_chain BEFORE INSERT ON ledger
BEGIN
    SELECT RAISE(ABORT, 'LEDGER: an entry with this id already exists')
    WHERE EXISTS (SELECT 1 FROM ledger WHERE id = NEW.id);

    SELECT RAISE(ABORT, 'LEDGER: prev_hash does not match the current chain tip')
    WHERE NEW.prev_hash <> COALESCE(
        (SELECT entry_hash FROM ledger ORDER BY id DESC LIMIT 1),
        hex(zeroblob(32)));
END;

-- TRIGGERS: REVERSAL RULES
CREATE TRIGGER trg_ledger_reversal BEFORE INSERT ON ledger
WHEN NEW.reversal_of IS NOT NULL
BEGIN
    SELECT RAISE(ABORT, 'REVERSAL: original entry not found')
    WHERE NOT EXISTS (SELECT 1 FROM ledger WHERE id = NEW.reversal_of);

    SELECT RAISE(ABORT, 'REVERSAL: a reversal cannot itself be reversed')
    WHERE EXISTS (SELECT 1 FROM ledger WHERE id = NEW.reversal_of AND reversal_of IS NOT NULL);

    SELECT RAISE(ABORT, 'REVERSAL: must exactly negate the original (same type, customer, invoice, opposite amount)')
    WHERE NOT EXISTS (
        SELECT 1 FROM ledger o
        WHERE o.id = NEW.reversal_of
          AND o.amount_cents = -NEW.amount_cents
          AND o.entry_type   = NEW.entry_type
          AND o.customer_id IS NEW.customer_id
          AND o.invoice_id  IS NEW.invoice_id);

    SELECT RAISE(ABORT, 'REVERSAL: reverse the invoice payments before cancelling its credit sale')
    WHERE NEW.entry_type = 'CREDIT_SALE'
      AND (SELECT COALESCE(SUM(amount_cents),0) FROM ledger
           WHERE invoice_id = NEW.invoice_id AND entry_type = 'PAYMENT') <> 0;
END;

-- TRIGGERS: BUSINESS RULES FOR CREDIT SALES AND PAYMENTS
CREATE TRIGGER trg_ledger_credit_sale BEFORE INSERT ON ledger
WHEN NEW.entry_type = 'CREDIT_SALE' AND NEW.reversal_of IS NULL
BEGIN
    SELECT RAISE(ABORT, 'CREDIT_SALE: must match the invoice customer and total exactly')
    WHERE NOT EXISTS (SELECT 1 FROM invoices
                      WHERE id = NEW.invoice_id
                        AND customer_id = NEW.customer_id
                        AND total_cents = NEW.amount_cents);

    SELECT RAISE(ABORT, 'CREDIT_SALE: this invoice has already been billed')
    WHERE EXISTS (SELECT 1 FROM ledger
                  WHERE invoice_id = NEW.invoice_id
                    AND entry_type = 'CREDIT_SALE' AND reversal_of IS NULL);
END;

CREATE TRIGGER trg_ledger_payment BEFORE INSERT ON ledger
WHEN NEW.entry_type = 'PAYMENT' AND NEW.reversal_of IS NULL
BEGIN
    SELECT RAISE(ABORT, 'PAYMENT: invoice does not belong to this customer')
    WHERE NOT EXISTS (SELECT 1 FROM invoices
                      WHERE id = NEW.invoice_id AND customer_id = NEW.customer_id);

    SELECT RAISE(ABORT, 'PAYMENT: exceeds the outstanding balance of the invoice')
    WHERE NEW.amount_cents >
          (SELECT COALESCE(SUM(amount_cents),0) FROM ledger
            WHERE invoice_id = NEW.invoice_id AND entry_type = 'CREDIT_SALE')
        - (SELECT COALESCE(SUM(amount_cents),0) FROM ledger
            WHERE invoice_id = NEW.invoice_id AND entry_type = 'PAYMENT');
END;

-- VIEW: per-invoice balance (used later by the Dues Collector)
CREATE VIEW v_invoice_balance AS
SELECT *,
       billed_cents - paid_cents AS outstanding_cents,
       MAX(0, CAST(julianday(date('now')) - julianday(due_date) AS INTEGER)) AS days_overdue
FROM (
    SELECT i.id AS invoice_id, i.invoice_no, i.customer_id, i.issue_date, i.due_date,
           COALESCE(SUM(CASE WHEN l.entry_type = 'CREDIT_SALE' THEN l.amount_cents END), 0) AS billed_cents,
           COALESCE(SUM(CASE WHEN l.entry_type = 'PAYMENT'     THEN l.amount_cents END), 0) AS paid_cents
    FROM invoices i
    LEFT JOIN ledger l ON l.invoice_id = i.id
    GROUP BY i.id
);

