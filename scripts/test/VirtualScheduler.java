package com.castilhoduarte.jlh6;

import java.util.ArrayList;
import java.util.List;

/** Deterministic virtual-time scheduler. Runnables fire in (fireAt, seq) order
 *  when advance() crosses their time. Tasks scheduled by running tasks are
 *  honored within the same advance() call. No real waiting; sleep() is a no-op. */
public final class VirtualScheduler implements Scheduler {
    private static final class Task {
        final long at; final long seq; final Runnable r;
        Task(long at, long seq, Runnable r) { this.at = at; this.seq = seq; this.r = r; }
    }
    private final FakeClock clock;
    private final List<Task> queue = new ArrayList<>();
    private long seq = 0;

    public VirtualScheduler(FakeClock clock) { this.clock = clock; }

    @Override public void post(Runnable r) { queue.add(new Task(clock.nowMs(), seq++, r)); }
    @Override public void postDelayed(Runnable r, long delayMs) { queue.add(new Task(clock.nowMs() + delayMs, seq++, r)); }
    @Override public void removeAll() { queue.clear(); }
    @Override public void sleep(long ms) { /* virtual time: no real wait */ }

    public int pending() { return queue.size(); }

    public void advance(long deltaMs) {
        long target = clock.nowMs() + deltaMs;
        while (true) {
            Task next = null; int idx = -1;
            for (int i = 0; i < queue.size(); i++) {
                Task t = queue.get(i);
                if (t.at <= target && (next == null || t.at < next.at || (t.at == next.at && t.seq < next.seq))) {
                    next = t; idx = i;
                }
            }
            if (next == null) break;
            queue.remove(idx);
            if (next.at > clock.nowMs()) clock.set(next.at);
            next.r.run();
        }
        if (target > clock.nowMs()) clock.set(target);
    }
}
