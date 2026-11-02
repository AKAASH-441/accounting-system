package com.accounting.ui;

import com.accounting.dao.CustomerDao;
import com.accounting.model.Customer;
import com.accounting.service.DuesService;
import com.accounting.service.LedgerService;
import com.accounting.util.Money;

import javax.swing.*;
import java.awt.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;

/** Add Transaction: sale, expense, credit sale (invoice) or customer payment. */
public class AddTransactionPanel extends JPanel {

    private static final class Item {
        final long id; final String label;
        Item(long id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return label; }
    }

    private final Connection conn;
    private final LedgerService service;
    private final DuesService dues;
    private final CustomerDao customers;
    private final Runnable onChange;

    private final JComboBox<String> type = new JComboBox<>(new String[] {
            "Sale (cash)", "Expense", "Credit sale (invoice)", "Payment received" });
    private final JTextField date = new JTextField(LocalDate.now().toString(), 12);
    private final JTextField category = new JTextField(20);
    private final JTextField description = new JTextField(30);
    private final JTextField amount = new JTextField(12);
    private final JComboBox<Item> customer = new JComboBox<>();
    private final JTextField invoiceNo = new JTextField(12);
    private final JTextField dueDate = new JTextField(LocalDate.now().plusDays(14).toString(), 12);
    private final JComboBox<Item> invoice = new JComboBox<>();
    private final JButton newCustomer = new JButton("New customer...");

    public AddTransactionPanel(Connection conn, Runnable onChange) {
        this.conn = conn;
        this.service = new LedgerService(conn);
        this.dues = new DuesService(conn);
        this.customers = new CustomerDao(conn);
        this.onChange = onChange;
        setLayout(new BorderLayout());
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;
        int r = 0;
        r = row(form, c, r, "Type", type);
        r = row(form, c, r, "Date (yyyy-mm-dd)", date);
        r = row(form, c, r, "Category", category);
        r = row(form, c, r, "Description", description);
        r = row(form, c, r, "Amount", amount);
        JPanel custRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        custRow.add(customer); custRow.add(Box.createHorizontalStrut(8)); custRow.add(newCustomer);
        r = row(form, c, r, "Customer", custRow);
        r = row(form, c, r, "Invoice number", invoiceNo);
        r = row(form, c, r, "Due date (yyyy-mm-dd)", dueDate);
        r = row(form, c, r, "Invoice being paid", invoice);

        JButton save = new JButton("Save to ledger");
        c.gridx = 1; c.gridy = r;
        form.add(save, c);

        add(form, BorderLayout.NORTH);
        type.addActionListener(e -> updateEnabled());
        newCustomer.addActionListener(e -> addCustomer());
        save.addActionListener(e -> save());
        reload();
    }

    private static int row(JPanel p, GridBagConstraints c, int r, String label, Component field) {
        c.gridx = 0; c.gridy = r; p.add(new JLabel(label), c);
        c.gridx = 1; p.add(field, c);
        return r + 1;
    }

    /** Re-reads the customer and unpaid-invoice lists. */
    public void reload() {
        customer.removeAllItems();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, name FROM customers ORDER BY name")) {
            while (rs.next()) customer.addItem(new Item(rs.getLong(1), rs.getString(2)));
        } catch (SQLException e) { UiUtil.error(this, e); }

        invoice.removeAllItems();
        for (DuesService.Due d : dues.chaseList(LocalDate.now())) {
            invoice.addItem(new Item(d.getInvoiceId(), d.getInvoiceNo() + " - " + d.getCustomerName()
                    + " - owes " + Money.format(d.getOutstandingCents())));
        }
        updateEnabled();
    }

    private void updateEnabled() {
        int t = type.getSelectedIndex();
        boolean credit = t == 2, payment = t == 3;
        category.setEnabled(t == 0 || t == 1);
        description.setEnabled(t == 0 || t == 1);
        customer.setEnabled(credit);
        newCustomer.setEnabled(credit);
        invoiceNo.setEnabled(credit);
        dueDate.setEnabled(credit);
        invoice.setEnabled(payment);
    }

    private void addCustomer() {
        JTextField name = new JTextField(20), phone = new JTextField(15);
        Object[] msg = { "Name", name, "Phone (optional)", phone };
        if (JOptionPane.showConfirmDialog(this, msg, "New customer", JOptionPane.OK_CANCEL_OPTION)
                != JOptionPane.OK_OPTION) return;
        try {
            if (name.getText().isBlank()) throw new IllegalArgumentException("Name is required");
            customers.insert(new Customer(name.getText().trim(),
                    phone.getText().isBlank() ? null : phone.getText().trim(), null));
            reload();
            customer.setSelectedIndex(customer.getItemCount() - 1);
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }

    private void save() {
        try {
            LocalDate d = UiUtil.parseDate(date.getText());
            long cents = UiUtil.parseMoney(amount.getText());
            switch (type.getSelectedIndex()) {
                case 0: service.recordSale(d, category.getText().trim(), description.getText().trim(), cents); break;
                case 1: service.recordExpense(d, category.getText().trim(), description.getText().trim(), cents); break;
                case 2: {
                    Item cust = (Item) customer.getSelectedItem();
                    if (cust == null) throw new IllegalArgumentException("Add or select a customer first");
                    if (invoiceNo.getText().isBlank()) throw new IllegalArgumentException("Invoice number is required");
                    service.recordCreditSale(invoiceNo.getText().trim(), cust.id, d,
                            UiUtil.parseDate(dueDate.getText()), cents);
                    break;
                }
                default: {
                    Item inv = (Item) invoice.getSelectedItem();
                    if (inv == null) throw new IllegalArgumentException("There are no unpaid invoices");
                    service.recordPayment(inv.id, d, cents);
                }
            }
            amount.setText("");
            description.setText("");
            invoiceNo.setText("");
            onChange.run();
            JOptionPane.showMessageDialog(this, "Saved to the ledger.");
        } catch (Exception ex) { UiUtil.error(this, ex); }
    }
}
