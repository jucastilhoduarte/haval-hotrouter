package com.castilhoduarte.jlh6;

public class VirtualSchedulerTest {
    static int passed = 0, failed = 0;
    static void check(String n, boolean c) {
        if (c) { passed++; System.out.println("  ok   " + n); }
        else { failed++; System.out.println("  FAIL " + n); }
    }

    public static void main(String[] a) {
        FakeClock clock = new FakeClock();
        VirtualScheduler s = new VirtualScheduler(clock);

        StringBuilder order = new StringBuilder();
        s.post(() -> order.append("A"));
        s.postDelayed(() -> order.append("C"), 100);
        s.postDelayed(() -> order.append("B"), 10);
        check("nothing fires before advance", order.length() == 0);

        s.advance(0);
        check("post fires at advance(0)", order.toString().equals("A"));

        s.advance(50);
        check("delayed<=50 fires in time order", order.toString().equals("AB"));
        check("clock moved to 50", clock.nowMs() == 50);

        s.advance(100);
        check("remaining delayed fires", order.toString().equals("ABC"));

        // cascading: a task scheduling another within the same advance
        StringBuilder casc = new StringBuilder();
        s.postDelayed(() -> { casc.append("1"); s.postDelayed(() -> casc.append("2"), 5); }, 5);
        s.advance(20);
        check("cascaded task runs same advance", casc.toString().equals("12"));

        // removeAll cancels
        boolean[] ran = {false};
        s.postDelayed(() -> ran[0] = true, 5);
        check("pending before removeAll", s.pending() == 1);
        s.removeAll();
        check("removeAll clears pending", s.pending() == 0);
        s.advance(100);
        check("removed task never runs", !ran[0]);

        System.out.println("\n" + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
