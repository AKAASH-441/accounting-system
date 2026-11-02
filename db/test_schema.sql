PRAGMA foreign_keys = ON;

INSERT INTO customers(name, phone) VALUES ('Ravi Traders', '9876543210');
INSERT INTO invoices(invoice_no, customer_id, issue_date, due_date, total_cents)
VALUES ('INV-001', 1, '2026-10-01', '2026-10-15', 500000);

-- T1 (should SUCCEED): genesis row, credit sale of 5,000.00
INSERT INTO ledger(entry_date, entry_type, category, description, amount_cents,
                   customer_id, invoice_id, created_at, prev_hash, entry_hash)
VALUES ('2026-10-01','CREDIT_SALE','Sales','Invoice INV-001',500000,1,1,
        '2026-10-01 10:00:00', hex(zeroblob(32)), replace(hex(zeroblob(32)),'0','a'));

-- T2 (should SUCCEED): partial payment of 2,000.00
INSERT INTO ledger(entry_date, entry_type, category, description, amount_cents,
                   customer_id, invoice_id, created_at, prev_hash, entry_hash)
VALUES ('2026-10-20','PAYMENT','Receipts','Part payment INV-001',200000,1,1,
        '2026-10-20 11:00:00', replace(hex(zeroblob(32)),'0','a'), replace(hex(zeroblob(32)),'0','b'));

-- T3 (should FAIL: updates not allowed)
UPDATE ledger SET amount_cents = 1 WHERE id = 1;

-- T4 (should FAIL: deletes not allowed)
DELETE FROM ledger WHERE id = 1;

-- T5 (should FAIL: wrong prev_hash)
INSERT INTO ledger(entry_date, entry_type, description, amount_cents, created_at, prev_hash, entry_hash)
VALUES ('2026-10-21','SALE','Counter sale',10000,'2026-10-21 09:00:00',
        replace(hex(zeroblob(32)),'0','f'), replace(hex(zeroblob(32)),'0','c'));

-- T6 (should FAIL: overpayment)
INSERT INTO ledger(entry_date, entry_type, description, amount_cents,
                   customer_id, invoice_id, created_at, prev_hash, entry_hash)
VALUES ('2026-10-22','PAYMENT','Too much',400000,1,1,'2026-10-22 09:00:00',
        replace(hex(zeroblob(32)),'0','b'), replace(hex(zeroblob(32)),'0','c'));

-- T7 (should SUCCEED): expense of 1,500.00 (negative amount)
INSERT INTO ledger(entry_date, entry_type, category, description, amount_cents,
                   created_at, prev_hash, entry_hash)
VALUES ('2026-10-23','EXPENSE','Rent','Shop rent',-150000,'2026-10-23 09:00:00',
        replace(hex(zeroblob(32)),'0','b'), replace(hex(zeroblob(32)),'0','c'));

-- T8 (should SUCCEED): reversal of the expense (id 3)
INSERT INTO ledger(entry_date, entry_type, category, description, amount_cents,
                   reversal_of, created_at, prev_hash, entry_hash)
VALUES ('2026-10-24','EXPENSE','Rent','REVERSAL: rent entered twice',150000,3,
        '2026-10-24 09:00:00', replace(hex(zeroblob(32)),'0','c'), replace(hex(zeroblob(32)),'0','d'));

-- T9 (should FAIL: already reversed)
INSERT INTO ledger(entry_date, entry_type, description, amount_cents,
                   reversal_of, created_at, prev_hash, entry_hash)
VALUES ('2026-10-25','EXPENSE','Second reversal',150000,3,
        '2026-10-25 09:00:00', replace(hex(zeroblob(32)),'0','d'), replace(hex(zeroblob(32)),'0','e'));

-- T10: derived balance. Expected: billed 500000, paid 200000, outstanding 300000
SELECT invoice_no, billed_cents, paid_cents, outstanding_cents, days_overdue FROM v_invoice_balance;
