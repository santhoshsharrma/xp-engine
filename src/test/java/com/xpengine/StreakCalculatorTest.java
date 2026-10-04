package com.xpengine;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StreakCalculatorTest {
    private static final LocalDate D = LocalDate.of(2026, 10, 4);

    @Test void firstEverEventStartsStreakAtOne() { assertEquals(1, StreakCalculator.next(null, 0, D)); }
    @Test void sameDayKeepsStreak()              { assertEquals(5, StreakCalculator.next(D, 5, D)); }
    @Test void nextDayIncrements()               { assertEquals(6, StreakCalculator.next(D.minusDays(1), 5, D)); }
    @Test void missedOneDayResets()              { assertEquals(1, StreakCalculator.next(D.minusDays(2), 5, D)); }
    @Test void missedManyDaysResets()            { assertEquals(1, StreakCalculator.next(D.minusDays(30), 9, D)); }
    @Test void monthBoundaryStillCountsAsNextDay() {
        assertEquals(4, StreakCalculator.next(LocalDate.of(2026, 9, 30), 3, LocalDate.of(2026, 10, 1)));
    }
}
