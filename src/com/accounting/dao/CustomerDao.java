package com.accounting.dao;

import com.accounting.model.Customer;
import com.accounting.util.Dates;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class CustomerDao {
    private final Connection conn;

    public CustomerDao(Connection conn) { this.conn = conn; }

    /** Inserts a customer and returns the generated id. */
    public long insert(Customer c) {
        String sql = "INSERT INTO customers(name, phone, email) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, c.getName());
            ps.setString(2, c.getPhone());
            ps.setString(3, c.getEmail());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not insert customer", e);
        }
    }

    public Optional<Customer> findById(long id) {
        String sql = "SELECT * FROM customers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not load customer " + id, e);
        }
    }

    public List<Customer> findAll() {
        List<Customer> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT * FROM customers ORDER BY name")) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new DataAccessException("Could not list customers", e);
        }
        return list;
    }

    /** Customers are editable master data (name/phone/email). */
    public void update(Customer c) {
        if (c.getId() == null) throw new IllegalArgumentException("Customer has no id yet");
        String sql = "UPDATE customers SET name = ?, phone = ?, email = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, c.getName());
            ps.setString(2, c.getPhone());
            ps.setString(3, c.getEmail());
            ps.setLong(4, c.getId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new DataAccessException("Could not update customer " + c.getId(), e);
        }
    }

    private static Customer map(ResultSet rs) throws SQLException {
        return new Customer(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("phone"),
                rs.getString("email"),
                Dates.timestampFromDb(rs.getString("created_at")));
    }
}
