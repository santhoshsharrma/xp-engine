package com.xpengine;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** Pure function: easy to unit test, no clock, no DB. "today" is the user's local date. */
public final class StreakCalculator {
    private StreakCalculator() {}

    public static int next(LocalDate lastActive, int currentStreak, LocalDate today) {
        if (lastActive == null) return 1;
        long gap = ChronoUnit.DAYS.between(lastActive, today);
        if (gap <= 0) return Math.max(currentStreak, 1); // same day (or clock skew): no change
        if (gap == 1) return currentStreak + 1;
        return 1;                                         // missed a day: reset
    }
}
