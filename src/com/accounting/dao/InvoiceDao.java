package com.accounting.dao;

import com.accounting.model.Invoice;
import com.accounting.model.InvoiceBalance;
import com.accounting.util.Dates;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class InvoiceDao {
    private final Connection conn;

    public InvoiceDao(Connection conn) { this.conn = conn; }

    /** Inserts an invoice (immutable afterwards) and returns the generated id. */
    public long insert(Invoice inv) {
        String sql = "INSERT INTO invoices(invoice_no, customer_id, issue_date, due_date, total_cents) "
                   + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, inv.getInvoiceNo());
            ps.setLong(2, inv.getCustomerId());
            ps.setString(3, Dates.toDb(inv.getIssueDate()));
            ps.setString(4, Dates.toDb(inv.getDueDate()));
            ps.setLong(5, inv.getTotalCents());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not insert invoice " + inv.getInvoiceNo(), e);
        }
    }

    public Optional<Invoice> findById(long id) {
        String sql = "SELECT * FROM invoices WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load invoice " + id, e);
        }
    }

    public List<Invoice> findByCustomer(long customerId) {
        List<Invoice> list = new ArrayList<>();
        String sql = "SELECT * FROM invoices WHERE customer_id = ? ORDER BY issue_date, id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not list invoices for customer " + customerId, e);
        }
        return list;
    }

    public List<Invoice> findAll() {
        List<Invoice> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM invoices ORDER BY issue_date, id")) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new DataAccessException("Could not list invoices", e);
        }
        return list;
    }

    // ---- balances (read from the v_invoice_balance view) ----

    public Optional<InvoiceBalance> findBalance(long invoiceId) {
        String sql = "SELECT * FROM v_invoice_balance WHERE invoice_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapBalance(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load balance for invoice " + invoiceId, e);
        }
    }

    /** All invoices that still have money due, oldest due date first. (Feeds the Dues Collector.) */
    public List<InvoiceBalance> findOutstandingBalances() {
        List<InvoiceBalance> list = new ArrayList<>();
        String sql = "SELECT * FROM v_invoice_balance WHERE outstanding_cents > 0 ORDER BY due_date, invoice_id";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) list.add(mapBalance(rs));
        } catch (SQLException e) {
            throw new DataAccessException("Could not list outstanding balances", e);
        }
        return list;
    }

    private static Invoice map(ResultSet rs) throws SQLException {
        return new Invoice(
                rs.getLong("id"),
                rs.getString("invoice_no"),
                rs.getLong("customer_id"),
                Dates.dateFromDb(rs.getString("issue_date")),
                Dates.dateFromDb(rs.getString("due_date")),
                rs.getLong("total_cents"),
                Dates.timestampFromDb(rs.getString("created_at")));
    }

    private static InvoiceBalance mapBalance(ResultSet rs) throws SQLException {
        return new InvoiceBalance(
                rs.getLong("invoice_id"),
                rs.getString("invoice_no"),
                rs.getLong("customer_id"),
                Dates.dateFromDb(rs.getString("issue_date")),
                Dates.dateFromDb(rs.getString("due_date")),
                rs.getLong("billed_cents"),
                rs.getLong("paid_cents"),
                rs.getLong("outstanding_cents"),
                rs.getInt("days_overdue"));
    }
}
