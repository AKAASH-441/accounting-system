package com.accounting;

import com.accounting.dao.CustomerDao;
import com.accounting.dao.LedgerDao;
import com.accounting.db.Database;
import com.accounting.model.Customer;
import com.accounting.service.DuesService;
import com.accounting.service.DuesService.Due;
import com.accounting.service.DuesService.Tone;
import com.accounting.service.HealthScoreService;
import com.accounting.service.LedgerService;
import com.accounting.util.Money;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;

/** Step 4 test. Works on a throw-away COPY of the database; your real one is never touched. */
public class Step4Check {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        Path real = Paths.get("db", "accounting.db");
        Path tmp  = Paths.get("out", "step4_test.db");
        Files.copy(real, tmp, StandardCopyOption.REPLACE_EXISTING);
        Database.setPath(tmp);
        System.out.println("Throw-away copy: " + Database.getPath());
        try (Connection conn = Database.getConnection()) {
            run(conn);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    private static void run(Connection conn) throws Exception {
        LocalDate today = LocalDate.of(2026, 11, 2);
        LedgerDao ledger = new LedgerDao(conn);
        LedgerService svc = new LedgerService(conn);
        DuesService dues = new DuesService(conn);
        HealthScoreService health = new HealthScoreService(conn);
        CustomerDao customers = new CustomerDao(conn);

        check("Test copy starts with an empty ledger", ledger.count() == 0);
        check("Empty ledger scores a neutral 80", health.compute(today).getTotal() == 80);
        check("Empty ledger has an empty chase list", dues.chaseList(today).isEmpty());

        long ravi = customers.insert(new Customer("Ravi Traders", "9876543210", null));
        long slow = customers.insert(new Customer("Slow Co", "9000000001", null));
        long mid  = customers.insert(new Customer("Mid Ltd", "9000000002", null));

        long r1 = svc.recordCreditSale("INV-R1", ravi, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 15), 100000);
        svc.recordPayment(r1, LocalDate.of(2026, 9, 16), 100000);
        svc.recordCreditSale("INV-R2", ravi, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 28), 80000);

        long s1 = svc.recordCreditSale("INV-S1", slow, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 15), 200000);
        svc.recordPayment(s1, LocalDate.of(2026, 9, 20), 200000);
        svc.recordCreditSale("INV-S2", slow, LocalDate.of(2026, 9, 25), LocalDate.of(2026, 10, 10), 300000);

        long m1 = svc.recordCreditSale("INV-M1", mid, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 20), 150000);

        svc.recordSale(LocalDate.of(2026, 10, 25), "Sales", "Counter sales", 400000);
        svc.recordExpense(LocalDate.of(2026, 10, 20), "Supplies", "Stock", 100000);
        svc.recordExpense(LocalDate.of(2026, 9, 20), "Supplies", "Stock", 100000);

        List<Due> list = dues.chaseList(today);
        for (Due d : list) {
            System.out.println("        CHASE: " + d.getCustomerName() + " " + d.getInvoiceNo()
                    + " owes " + Money.format(d.getOutstandingCents()) + ", " + d.getDaysOverdue()
                    + " days overdue, avg late " + (d.getAvgDaysLate() == null ? "n/a" : d.getAvgDaysLate())
                    + ", tone: " + d.getTone().getLabel());
        }
        check("Three customers owe money", list.size() == 3);
        check("Ranked by risk: Slow Co, Mid Ltd, Ravi Traders",
              list.get(0).getCustomerName().equals("Slow Co")
              && list.get(1).getCustomerName().equals("Mid Ltd")
              && list.get(2).getCustomerName().equals("Ravi Traders"));
        Due dSlow = list.get(0), dMid = list.get(1), dRavi = list.get(2);

        check("Slow Co owes 3,000.00", dSlow.getOutstandingCents() == 300000);
        check("Slow Co is 23 days overdue", dSlow.getDaysOverdue() == 23);
        check("Slow Co averages 36 days late",
              dSlow.getAvgDaysLate() != null && Math.abs(dSlow.getAvgDaysLate() - 36.0) < 0.001);
        check("Slow Co gets a Final notice", dSlow.getTone() == Tone.FINAL_NOTICE);

        check("Mid Ltd is 13 days overdue with no payment history",
              dMid.getDaysOverdue() == 13 && dMid.getAvgDaysLate() == null);
        check("Mid Ltd gets a Firm reminder", dMid.getTone() == Tone.FIRM);

        check("Ravi is 5 days overdue and averages 1 day late",
              dRavi.getDaysOverdue() == 5 && dRavi.getAvgDaysLate() != null
              && Math.abs(dRavi.getAvgDaysLate() - 1.0) < 0.001);
        check("Ravi gets a Friendly reminder", dRavi.getTone() == Tone.FRIENDLY);

        HealthScoreService.Result h = health.compute(today);
        System.out.println("        HEALTH: liquidity " + h.getLiquidity() + "/40, receivables "
                + h.getReceivables() + "/30, velocity " + h.getVelocity() + "/30, total " + h.getTotal());
        check("Liquidity is 40/40 (income 4,000 vs expenses 1,000)", h.getLiquidity() == 40);
        check("Receivables is 0/30 (everything owed is overdue)", h.getReceivables() == 0);
        check("Velocity is 30/30 (expenses flat versus last month)", h.getVelocity() == 30);
        check("Total score is 70", h.getTotal() == 70);
        check("A weak receivables score produces advice", !h.getAdvice().isEmpty());

        svc.recordExpense(LocalDate.of(2026, 10, 30), "Equipment", "New machine", 900000);
        HealthScoreService.Result bad = health.compute(today);
        for (String a : bad.getAdvice()) System.out.println("        ADVICE: " + a);
        check("After a large expense the score drops to 0", bad.getTotal() == 0);
        check("Low score shows the critical cash-reserves advice",
              bad.getAdvice().stream().anyMatch(a -> a.contains("critically low")));

        svc.recordPayment(m1, LocalDate.of(2026, 11, 1), 150000);
        List<Due> after = dues.chaseList(today);
        check("Chase list shrinks to 2 after Mid Ltd pays in full", after.size() == 2);
        check("Mid Ltd is no longer on the chase list",
              after.stream().noneMatch(d -> d.getCustomerName().equals("Mid Ltd")));
    }

    private static void check(String label, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + label);
    }
}
