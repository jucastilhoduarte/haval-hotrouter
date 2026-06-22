package com.castilhoduarte.jlh6;

public final class FakeClock implements Clock {
    private long now = 0;
    @Override public long nowMs() { return now; }
    public void set(long t) { now = t; }
}
