package com.accounting.ui;

import com.accounting.service.DuesService;
import com.accounting.service.HealthScoreService;
import com.accounting.util.Money;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.sql.Connection;
import java.time.LocalDate;

/** Dashboard: health score, its three factors, advice, and the ranked chase list. */
public class DashboardPanel extends JPanel {
    private final HealthScoreService health;
    private final DuesService dues;

    private final JLabel scoreLabel = new JLabel("--", SwingConstants.CENTER);
    private final JProgressBar liquidity = bar(40);
    private final JProgressBar receivables = bar(30);
    private final JProgressBar velocity = bar(30);
    private final JTextArea advice = new JTextArea(4, 40);
    private final DefaultTableModel model = UiUtil.readOnlyModel(
            "Customer", "Invoice", "Outstanding", "Days overdue", "Avg days late", "Risk", "Suggested tone");

    public DashboardPanel(Connection conn) {
        this.health = new HealthScoreService(conn);
        this.dues = new DuesService(conn);
        setLayout(new BorderLayout(10, 10));
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        scoreLabel.setFont(scoreLabel.getFont().deriveFont(Font.BOLD, 56f));
        JPanel scoreBox = new JPanel(new BorderLayout());
        scoreBox.setBorder(BorderFactory.createTitledBorder("Business health (0-100)"));
        scoreBox.add(scoreLabel, BorderLayout.CENTER);

        JPanel factors = new JPanel(new GridLayout(3, 2, 6, 6));
        factors.setBorder(BorderFactory.createTitledBorder("What drives the score"));
        factors.add(new JLabel("Liquidity (cash flow, 30 days)"));      factors.add(liquidity);
        factors.add(new JLabel("Receivables (overdue money owed)"));    factors.add(receivables);
        factors.add(new JLabel("Expense velocity (vs last month)"));    factors.add(velocity);

        JPanel top = new JPanel(new GridLayout(1, 2, 10, 0));
        top.add(scoreBox);
        top.add(factors);

        advice.setEditable(false);
        advice.setLineWrap(true);
        advice.setWrapStyleWord(true);
        JPanel adviceBox = new JPanel(new BorderLayout());
        adviceBox.setBorder(BorderFactory.createTitledBorder("Advice"));
        adviceBox.add(new JScrollPane(advice), BorderLayout.CENTER);

        JPanel north = new JPanel(new BorderLayout(0, 10));
        north.add(top, BorderLayout.NORTH);
        north.add(adviceBox, BorderLayout.CENTER);

        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Smart Dues Collector: who to chase first"));

        add(north, BorderLayout.NORTH);
        add(tableScroll, BorderLayout.CENTER);
        refresh();
    }

    public void refresh() {
        LocalDate today = LocalDate.now();
        HealthScoreService.Result r = health.compute(today);
        scoreLabel.setText(Integer.toString(r.getTotal()));
        scoreLabel.setForeground(r.getTotal() >= 70 ? new Color(0, 140, 60)
                : r.getTotal() >= 50 ? new Color(220, 140, 0) : new Color(200, 30, 30));
        set(liquidity, r.getLiquidity(), 40);
        set(receivables, r.getReceivables(), 30);
        set(velocity, r.getVelocity(), 30);
        advice.setText(r.getAdvice().isEmpty() ? "No action needed. Keep it up."
                : String.join("\n\n", r.getAdvice()));

        model.setRowCount(0);
        for (DuesService.Due d : dues.chaseList(today)) {
            model.addRow(new Object[] {
                    d.getCustomerName(), d.getInvoiceNo(), Money.format(d.getOutstandingCents()),
                    d.getDaysOverdue(),
                    d.getAvgDaysLate() == null ? "no history" : String.format("%.1f", d.getAvgDaysLate()),
                    String.format("%,.0f", d.getRiskScore() / 100.0),
                    d.getTone().getLabel() });
        }
    }

    private static JProgressBar bar(int max) {
        JProgressBar b = new JProgressBar(0, max);
        b.setStringPainted(true);
        return b;
    }

    private static void set(JProgressBar b, int value, int max) {
        b.setValue(value);
        b.setString(value + " / " + max);
    }
}
