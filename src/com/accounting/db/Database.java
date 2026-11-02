package com.accounting.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Single place that opens database connections.
 * Run the app from the AccountingSystem folder so "db/accounting.db" resolves correctly.
 */
public final class Database {

    private static Path dbPath = Paths.get("db", "accounting.db");

    static {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError("SQLite JDBC driver not found. Is lib\\sqlite-jdbc-*.jar on the classpath?");
        }
    }

    private Database() { }

    /** Override the database location (useful for tests). */
    public static void setPath(Path path) { dbPath = path; }

    public static Path getPath() { return dbPath.toAbsolutePath(); }

    /** Opens a NEW connection. The caller must close it (use try-with-resources). */
    public static Connection getConnection() throws SQLException {
        if (!Files.exists(dbPath)) {
            throw new SQLException("Database file not found: " + dbPath.toAbsolutePath()
                    + "  -> create it with: sqlite3 db\\accounting.db \".read db\\schema.sql\"");
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");   // per-connection setting; must come BEFORE any transaction
            st.execute("PRAGMA busy_timeout = 5000"); // wait up to 5s if the file is briefly locked
        } catch (SQLException e) {
            conn.close();
            throw e;
        }
        return conn;
    }
}
