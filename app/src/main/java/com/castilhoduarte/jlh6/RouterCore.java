package com.castilhoduarte.jlh6;

public final class RouterCore {

    public enum State { DISABLED, STARTING, ACTIVE, PURGING }

    private static final int RECOVERY_FAIL_THRESHOLD = 3;
    private static final long PING_INTERVAL_MS = 5_000L;
    private static final long PING_TIMEOUT_MS  = 10 * 60 * 1_000L;
    private static final int APPLY_ATTEMPTS = 3;
    private static final int PURGE_ATTEMPTS = 4;
    private static final long VERIFY_BACKOFF_MS = 500L;

    private static final String HOTSPOT_IF = "wlan2";
    private static final String STARLINK_IF = "wlan0";
    private static final String STARLINK_TABLE = "wlan0";
    private static final String RULE_PRIO = "17999";

    private final Clock clock;
    private final Scheduler scheduler;
    private final Shell shell;
    private final StateStore store;

    private volatile State state = State.DISABLED;
    private volatile boolean pingRunning = false;
    private volatile long pingStartMs = 0;
    private volatile boolean monitorRunning = false;
    private volatile int consecutiveFails = 0;

    public RouterCore(Clock clock, Scheduler scheduler, Shell shell, StateStore store) {
        this.clock = clock;
        this.scheduler = scheduler;
        this.shell = shell;
        this.store = store;
    }

    public State getState() { return state; }

    public boolean isAutoRecovery() { return store.isAutoRecovery(); }

    public void setAutoRecovery(boolean on) {
        store.setAutoRecovery(on);
        if (on) {
            if (state == State.ACTIVE) startMonitor();
        } else {
            stopMonitor();
        }
    }

    public void restoreIfEnabled() {
        if (store.isEnabled()) startPingLoop();
    }

    public void enable() {
        store.setEnabled(true);
        startPingLoop();
    }

    public void disable() {
        if (state == State.PURGING) return;
        pingRunning = false;
        monitorRunning = false;
        scheduler.removeAll();
        state = State.PURGING;
        store.setEnabled(false);
        store.setAutoRecovery(false);
        scheduler.post(() -> {
            execPurge();
            state = State.DISABLED;
        });
    }

    private void startPingLoop() {
        if (pingRunning) return;
        state = State.STARTING;
        pingRunning = true;
        pingStartMs = clock.nowMs();
        scheduler.post(this::doPing);
    }

    private void doPing() {
        if (!pingRunning) return;

        if (clock.nowMs() - pingStartMs > PING_TIMEOUT_MS) {
            pingRunning = false;
            monitorRunning = false;
            store.setEnabled(false);
            store.setAutoRecovery(false);
            execPurge();
            state = State.DISABLED;
            return;
        }

        boolean ok = ping();
        if (!pingRunning) return;

        if (ok) {
            if (!execApply()) {
                if (!pingRunning) return;
                scheduler.postDelayed(this::doPing, PING_INTERVAL_MS);
                return;
            }
            if (!pingRunning) return;
            state = State.ACTIVE;
            if (store.isAutoRecovery()) startMonitor();
        } else {
            scheduler.postDelayed(this::doPing, PING_INTERVAL_MS);
        }
    }

    private void startMonitor() {
        if (monitorRunning) return;
        monitorRunning = true;
        consecutiveFails = 0;
        scheduler.post(this::doMonitor);
    }

    private void stopMonitor() { monitorRunning = false; }

    private void doMonitor() {
        if (!monitorRunning) return;
        if (state != State.ACTIVE) { monitorRunning = false; return; }

        boolean ok = ping();
        if (!monitorRunning) return;

        if (ok) {
            consecutiveFails = 0;
            scheduler.postDelayed(this::doMonitor, PING_INTERVAL_MS);
        } else {
            consecutiveFails++;
            if (consecutiveFails < RECOVERY_FAIL_THRESHOLD) {
                scheduler.postDelayed(this::doMonitor, PING_INTERVAL_MS);
            } else {
                recover();
            }
        }
    }

    private void recover() {
        monitorRunning = false;
        consecutiveFails = 0;
        state = State.STARTING;
        execPurge();
        scheduler.postDelayed(() -> {
            if (!store.isEnabled()) return;
            pingRunning = false;
            startPingLoop();
        }, PING_INTERVAL_MS);
    }

    private boolean ping() {
        try {
            return shell.exec("ping -I " + STARLINK_IF + " -c 1 -W 2 8.8.8.8").ok();
        } catch (Throwable ignored) { return false; }
    }

    private boolean execVerified(String command, int attempts, long backoffMs, boolean cancelOnDisable) {
        for (int i = 0; i < attempts; i++) {
            if (cancelOnDisable && !pingRunning) return false;
            try {
                if (shell.exec(command).ok()) return true;
            } catch (Throwable ignored) {}
            if (i < attempts - 1) scheduler.sleep(backoffMs);
        }
        return false;
    }

    private boolean execApply() { return execVerified(applyCmd(), APPLY_ATTEMPTS, VERIFY_BACKOFF_MS, true); }
    private boolean execPurge() { return execVerified(purgeCmd(), PURGE_ATTEMPTS, VERIFY_BACKOFF_MS, false); }

    static String applyCmd() {
        return "echo 1 > /proc/sys/net/ipv4/ip_forward; "
            + "while ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "'; do ip rule del from all iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + " priority " + RULE_PRIO + " 2>/dev/null || break; done; "
            + "ip rule add from all iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + " priority " + RULE_PRIO + "; "
            + "iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null || iptables -t nat -I POSTROUTING 1 -o " + STARLINK_IF + " -j MASQUERADE; "
            + "iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT; "
            + "iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || iptables -I FORWARD 1 -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT; "
            + "grep -qx 1 /proc/sys/net/ipv4/ip_forward 2>/dev/null "
            + "&& ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "' "
            + "&& iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null "
            + "&& iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null "
            + "&& iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null";
    }

    static String purgeCmd() {
        return "while ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "'; do ip rule del from all iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + " priority " + RULE_PRIO + " 2>/dev/null || break; done; "
            + "while iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null; do iptables -t nat -D POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null || break; done; "
            + "while iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null || break; done; "
            + "while iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || break; done; "
            + "! ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "' "
            + "&& ! iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null "
            + "&& ! iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null "
            + "&& ! iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null";
    }
}
