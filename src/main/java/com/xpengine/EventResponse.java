package com.xpengine;

public record EventResponse(boolean duplicate, int xpAwarded, long totalXp, int streak, Long rank) {}
