package com.accounting.dao;

import com.accounting.model.EntryType;
import com.accounting.model.LedgerEntry;
import com.accounting.util.Dates;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Data access for the tamper-proof ledger. There is deliberately NO update() or delete():
 * the database triggers would reject them anyway. Computing hashes is NOT done here;
 * Step 3's LedgerService builds the hashed entry and passes it to insert().
 */
public class LedgerDao {
    private final Connection conn;

    public LedgerDao(Connection conn) { this.conn = conn; }

    /** Appends an already-hashed entry and returns its generated id. */
    public long insert(LedgerEntry e) {
        String sql = "INSERT INTO ledger(entry_date, entry_type, category, description, amount_cents, "
                   + "customer_id, invoice_id, reversal_of, created_at, prev_hash, entry_hash) "
                   + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, Dates.toDb(e.getEntryDate()));
            ps.setString(2, e.getEntryType().name());
            ps.setString(3, e.getCategory());
            ps.setString(4, e.getDescription());
            ps.setLong(5, e.getAmountCents());
            JdbcUtil.setLongOrNull(ps, 6, e.getCustomerId());
            JdbcUtil.setLongOrNull(ps, 7, e.getInvoiceId());
            JdbcUtil.setLongOrNull(ps, 8, e.getReversalOf());
            ps.setString(9, Dates.toDb(e.getCreatedAt()));
            ps.setString(10, e.getPrevHash());
            ps.setString(11, e.getEntryHash());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException ex) {
            throw new DataAccessException("Could not append ledger entry", ex);
        }
    }

    public Optional<LedgerEntry> findById(long id) {
        String sql = "SELECT * FROM ledger WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw new DataAccessException("Could not load ledger entry " + id, ex);
        }
    }

    /** The newest entry = the current chain tip (empty Optional means the ledger is empty). */
    public Optional<LedgerEntry> findLast() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM ledger ORDER BY id DESC LIMIT 1")) {
            return rs.next() ? Optional.of(map(rs)) : Optional.empty();
        } catch (SQLException ex) {
            throw new DataAccessException("Could not load the last ledger entry", ex);
        }
    }

    /** Every entry, oldest first. This is the order the audit engine (Step 3) walks. */
    public List<LedgerEntry> findAll() {
        return query("SELECT * FROM ledger ORDER BY id");
    }

    public List<LedgerEntry> findByInvoice(long invoiceId) {
        return query("SELECT * FROM ledger WHERE invoice_id = ? ORDER BY id", invoiceId);
    }

    public List<LedgerEntry> findByCustomer(long customerId) {
        return query("SELECT * FROM ledger WHERE customer_id = ? ORDER BY id", customerId);
    }

    /** Entries whose entry_date is within [from, to], inclusive. */
    public List<LedgerEntry> findBetween(LocalDate from, LocalDate to) {
        List<LedgerEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM ledger WHERE entry_date BETWEEN ? AND ? ORDER BY id";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, Dates.toDb(from));
            ps.setString(2, Dates.toDb(to));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new DataAccessException("Could not load ledger entries by date", ex);
        }
        return list;
    }

    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException ex) {
            throw new DataAccessException("Could not count ledger entries", ex);
        }
    }

    private List<LedgerEntry> query(String sql, Object... params) {
        List<LedgerEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException ex) {
            throw new DataAccessException("Ledger query failed", ex);
        }
        return list;
    }

    private static LedgerEntry map(ResultSet rs) throws SQLException {
        return new LedgerEntry(
                rs.getLong("id"),
                Dates.dateFromDb(rs.getString("entry_date")),
                EntryType.valueOf(rs.getString("entry_type")),
                rs.getString("category"),
                rs.getString("description"),
                rs.getLong("amount_cents"),
                JdbcUtil.getLongOrNull(rs, "customer_id"),
                JdbcUtil.getLongOrNull(rs, "invoice_id"),
                JdbcUtil.getLongOrNull(rs, "reversal_of"),
                Dates.timestampFromDb(rs.getString("created_at")),
                rs.getString("prev_hash"),
                rs.getString("entry_hash"));
    }
}
