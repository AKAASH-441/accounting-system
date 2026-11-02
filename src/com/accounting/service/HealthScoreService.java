package com.accounting.service;

import com.accounting.dao.LedgerDao;
import com.accounting.model.EntryType;
import com.accounting.model.LedgerEntry;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Business Health Score, 0-100. Read-only.
 *   Liquidity 40  : income vs expenses over the last 30 days (ratio 0.5 = 0 pts, 1.5 or more = full)
 *   Receivables 30: share of money owed to you that is overdue (0% overdue = full)
 *   Velocity 30   : expenses in the last 30 days vs the 30 days before (flat or falling = full, +50% = 0)
 * Income = SALE + PAYMENT entries. Reversals net out automatically because they carry the opposite sign.
 */
public class HealthScoreService {

    public static final class Result {
        private final int liquidity, receivables, velocity, total;
        private final List<String> advice;

        Result(int liquidity, int receivables, int velocity, List<String> advice) {
            this.liquidity = liquidity;
            this.receivables = receivables;
            this.velocity = velocity;
            this.total = liquidity + receivables + velocity;
            this.advice = Collections.unmodifiableList(advice);
        }
        public int getLiquidity()    { return liquidity; }
        public int getReceivables()  { return receivables; }
        public int getVelocity()     { return velocity; }
        public int getTotal()        { return total; }
        public List<String> getAdvice() { return advice; }
    }

    public static final int ADVICE_THRESHOLD = 50;

    private final LedgerDao ledger;
    private final DuesService dues;

    public HealthScoreService(Connection conn) {
        this.ledger = new LedgerDao(conn);
        this.dues = new DuesService(conn);
    }

    public Result compute(LocalDate today) {
        LocalDate curStart  = today.minusDays(29);   // last 30 days, inclusive of today
        LocalDate prevStart = today.minusDays(59);
        LocalDate prevEnd   = today.minusDays(30);

        long income = 0, expCur = 0, expPrev = 0;
        for (LedgerEntry e : ledger.findAll()) {
            LocalDate d = e.getEntryDate();
            boolean cur = !d.isBefore(curStart) && !d.isAfter(today);
            boolean prev = !d.isBefore(prevStart) && !d.isAfter(prevEnd);
            if (e.getEntryType() == EntryType.SALE || e.getEntryType() == EntryType.PAYMENT) {
                if (cur) income += e.getAmountCents();
            } else if (e.getEntryType() == EntryType.EXPENSE) {
                if (cur)  expCur  += -e.getAmountCents();     // stored negative
                if (prev) expPrev += -e.getAmountCents();
            }
        }
        expCur = Math.max(0, expCur);
        expPrev = Math.max(0, expPrev);

        // Liquidity (40)
        int liquidity;
        if (income <= 0 && expCur <= 0)      liquidity = 20;                       // no activity: neutral
        else if (expCur == 0)                liquidity = 40;
        else liquidity = (int) Math.round(40 * clamp((((double) income / expCur) - 0.5) / 1.0));

        // Receivables (30)
        long totalOwed = 0, overdue = 0;
        for (DuesService.Due d : dues.chaseList(today)) {
            totalOwed += d.getOutstandingCents();
            if (d.getDaysOverdue() > 0) overdue += d.getOutstandingCents();
        }
        int receivables = (totalOwed == 0) ? 30
                : (int) Math.round(30 * (1.0 - clamp((double) overdue / totalOwed)));

        // Expense velocity (30)
        int velocity;
        if (expPrev == 0) velocity = (expCur == 0) ? 30 : 15;                      // no baseline: neutral
        else velocity = (int) Math.round(30 * (1.0 - clamp((((double) expCur - expPrev) / expPrev) / 0.5)));

        List<String> advice = new ArrayList<>();
        int total = liquidity + receivables + velocity;
        if (total < ADVICE_THRESHOLD) {
            advice.add("Cash reserves are critically low. Delay non-essential supply purchases "
                    + "and prioritise collecting overdue payments.");
        }
        if (liquidity < 20) advice.add("Income over the last 30 days does not cover your expenses. "
                + "Review spending and push for sales.");
        if (receivables < 15) advice.add("More than half of the money owed to you is overdue. "
                + "Work through the Smart Dues Collector chase list.");
        if (velocity < 15) advice.add("Your expenses are growing quickly compared with last month. "
                + "Check which categories increased.");
        return new Result(liquidity, receivables, velocity, advice);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }
}
