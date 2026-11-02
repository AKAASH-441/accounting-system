package com.accounting.ui;

import com.accounting.db.Database;

import javax.swing.*;
import java.nio.file.Paths;
import java.sql.Connection;

/** Entry point. Optional argument: path of a database file to open instead of db\accounting.db. */
public class AppMain {
    public static void main(String[] args) throws Exception {
        if (args.length > 0) Database.setPath(Paths.get(args[0]));
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignore) { }
        final Connection conn = Database.getConnection();

        SwingUtilities.invokeLater(() -> {
            final Runnable[] changed = new Runnable[1];
            Runnable fire = () -> changed[0].run();

            DashboardPanel dashboard = new DashboardPanel(conn);
            AddTransactionPanel add = new AddTransactionPanel(conn, fire);
            LedgerPanel ledgerPanel = new LedgerPanel(conn, fire);
            changed[0] = () -> { dashboard.refresh(); ledgerPanel.refresh(); add.reload(); };

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Dashboard", dashboard);
            tabs.addTab("Add Transaction", add);
            tabs.addTab("Ledger", ledgerPanel);
            tabs.addChangeListener(e -> changed[0].run());

            JFrame f = new JFrame("Accounting System  -  " + Database.getPath());
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.add(tabs);
            f.setSize(1050, 720);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
        });
    }
}
