package com.xpengine;

/** XP is decided here, on the server. The client only says what happened. */
public enum EventType {
    LESSON_COMPLETED(50),
    QUIZ_PASSED(30),
    DAILY_LOGIN(10);

    public final int xp;
    EventType(int xp) { this.xp = xp; }
}
