package com.familyx.stocks.engine;

public enum ExitReason {
    TARGET("target hit"),
    STOP("stop-loss hit"),
    TRAILING_STOP("trailing stop hit"),
    MAX_HOLD("held for the maximum time"),
    SIGNAL("strategy exit signal"),
    ROTATION("rotated out");

    private final String label;

    ExitReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
