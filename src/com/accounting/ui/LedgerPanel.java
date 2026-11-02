package com.accounting.ui;

import com.accounting.dao.LedgerDao;
import com.accounting.model.LedgerEntry;
import com.accounting.service.AuditService;
import com.accounting.service.LedgerService;
import com.accounting.util.Money;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;

/** View Ledger: read-only table plus audit, reversal and anchor tools. */
public class LedgerPanel extends JPanel {
    private final LedgerDao ledger;
    private final LedgerService service;
    private final AuditService audit;
    private final Runnable onChange;

    private final DefaultTableModel model = UiUtil.readOnlyModel(
            "#", "Date", "Type", "Category", "Description", "Amount", "Hash (start)");
    private final JTable table = new JTable(model);
    private final JLabel status = new JLabel(" ");

    public LedgerPanel(Connection conn, Runnable onChange) {
        this.ledger = new LedgerDao(conn);
        this.service = new LedgerService(conn);
        this.audit = new AuditService(conn);
        this.onChange = onChange;
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(4).setPreferredWidth(320);

        JButton verify = new JButton("Verify integrity");
        JButton reverse = new JButton("Reverse selected entry");
        JButton export = new JButton("Export anchor");
        JButton check = new JButton("Check anchor");
        verify.addActionListener(e -> verify());
        reverse.addActionListener(e -> reverseSelected());
        export.addActionListener(e -> exportAnchor());
        check.addActionListener(e -> checkAnchor());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttons.add(verify); buttons.add(reverse); buttons.add(export); buttons.add(check);

        add(buttons, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        refresh();
    }

    public void refresh() {
        model.setRowCount(0);
        List<LedgerEntry> all = ledger.findAll();
        for (LedgerEntry e : all) {
            model.addRow(new Object[] {
                    Long.valueOf(e.getId()), e.getEntryDate(), e.getEntryType().name(), e.getCategory(),
                    e.getDescription(), Money.format(e.getAmountCents()),
                    e.getEntryHash().substring(0, 10) + "..." });
        }
        status.setText(all.size() + " entries. Entries cannot be edited or deleted; use Reverse to fix a mistake.");
    }

    private void verify() {
        try {
            AuditService.Report r = audit.verify();
            if (r.isOk()) {
                JOptionPane.showMessageDialog(this, "Ledger is intact.\n" + r.getEntriesChecked()
                        + " entries checked, no problems found.", "Audit passed", JOptionPane.INFORMATION_MESSAGE);
            } else {
                selectId(r.getFirstBadId());
                JOptionPane.showMessageDialog(this,
                        "TAMPERING DETECTED. First bad entry: #" + r.getFirstBadId() + "\n\n"
                                + String.join("\n", r.getProblems()),
                        "Audit FAILED", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }

    private void reverseSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Select an entry first.");
            return;
        }
        long id = ((Number) model.getValueAt(table.convertRowIndexToModel(row), 0)).longValue();
        String reason = JOptionPane.showInputDialog(this, "Why is entry #" + id + " being reversed?");
        if (reason == null) return;
        try {
            service.reverse(id, reason);
            onChange.run();
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }

    private void exportAnchor() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("ledger-anchor.txt"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Path p = fc.getSelectedFile().toPath();
            audit.exportAnchor(p);
            JOptionPane.showMessageDialog(this, "Anchor saved to:\n" + p
                    + "\n\nKeep a copy OUTSIDE this computer (USB stick, email to yourself).");
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }

    private void checkAnchor() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            List<String> problems = audit.verifyAnchor(fc.getSelectedFile().toPath());
            if (problems.isEmpty()) {
                JOptionPane.showMessageDialog(this, "The ledger matches the anchor file.");
            } else {
                JOptionPane.showMessageDialog(this, String.join("\n", problems),
                        "Ledger does NOT match the anchor", JOptionPane.ERROR_MESSAGE);
            }
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }

    private void selectId(Long id) {
        if (id == null) return;
        for (int i = 0; i < model.getRowCount(); i++) {
            if (((Number) model.getValueAt(i, 0)).longValue() == id) {
                int v = table.convertRowIndexToView(i);
                table.setRowSelectionInterval(v, v);
                table.scrollRectToVisible(table.getCellRect(v, 0, true));
                return;
            }
        }
    }
}
