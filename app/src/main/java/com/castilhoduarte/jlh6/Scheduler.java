package com.castilhoduarte.jlh6;

public interface Scheduler {
    void post(Runnable r);
    void postDelayed(Runnable r, long delayMs);
    void removeAll();
    void sleep(long ms);
}
