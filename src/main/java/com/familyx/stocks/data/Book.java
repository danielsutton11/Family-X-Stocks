package com.familyx.stocks.data;

/** Model portfolios. MAIN is what members follow (rotation); DIP is the dip strategy, tracked on paper. */
public enum Book {
    MAIN(1, "main"),
    DIP(2, "dip");

    private final int stateId;
    private final String key;

    Book(int stateId, String key) {
        this.stateId = stateId;
        this.key = key;
    }

    public int stateId() {
        return stateId;
    }

    public String key() {
        return key;
    }
}
