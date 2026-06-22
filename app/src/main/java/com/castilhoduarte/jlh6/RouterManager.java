package com.castilhoduarte.jlh6;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

public final class RouterManager {

    public enum State { DISABLED, STARTING, ACTIVE, PURGING }

    private static final String PREFS_NAME = "router";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_AUTO_RECOVERY = "auto_recovery";
    private static final int RECOVERY_FAIL_THRESHOLD = 3;
    private static final long PING_INTERVAL_MS = 5_000L;
    private static final long PING_TIMEOUT_MS  = 10 * 60 * 1_000L;
    private static final int CONNECT_MS = 2_000;
    private static final int READ_MS = 6_000;
    private static final int APPLY_ATTEMPTS = 3;
    private static final int PURGE_ATTEMPTS = 4;
    private static final long VERIFY_BACKOFF_MS = 500L;

    private static final String HOTSPOT_IF = "wlan2";
    private static final String STARLINK_IF = "wlan0";
    private static final String STARLINK_TABLE = "wlan0";
    private static final String RULE_PRIO = "17999";

    private static volatile RouterManager instance;

    public static RouterManager get() {
        if (instance == null) {
            synchronized (RouterManager.class) {
                if (instance == null) instance = new RouterManager();
            }
        }
        return instance;
    }

    private final Handler bg;
    private volatile State state = State.DISABLED;
    private volatile boolean pingRunning = false;
    private volatile long pingStartMs = 0;
    private volatile boolean monitorRunning = false;
    private volatile int consecutiveFails = 0;

    private RouterManager() {
        HandlerThread t = new HandlerThread("RouterManager");
        t.start();
        bg = new Handler(t.getLooper());
    }

    public State getState() {
        return state;
    }

    public boolean isAutoRecovery(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTO_RECOVERY, false);
    }

    public void setAutoRecovery(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTO_RECOVERY, on).commit();
        if (on) {
            if (state == State.ACTIVE) startMonitor(ctx);
        } else {
            stopMonitor();
        }
    }

    public void restoreIfEnabled(Context ctx) {
        if (prefs(ctx).getBoolean(KEY_ENABLED, false)) {
            startPingLoop(ctx);
        }
    }

    public void enable(Context ctx) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, true).commit();
        startPingLoop(ctx);
    }

    public void disable(Context ctx) {
        // Note: STARTING may be a recovery post-purge wait, not just first
        // activation — both are cancellable here (we clear flags + nuke bg).
        if (state == State.PURGING) return;
        pingRunning = false;
        monitorRunning = false;
        bg.removeCallbacksAndMessages(null);
        state = State.PURGING;
        prefs(ctx).edit()
                .putBoolean(KEY_ENABLED, false)
                .putBoolean(KEY_AUTO_RECOVERY, false)
                .commit();
        bg.post(() -> {
            execPurge();
            state = State.DISABLED;
        });
    }

    private void startPingLoop(Context ctx) {
        if (pingRunning) return;
        state = State.STARTING;
        pingRunning = true;
        pingStartMs = System.currentTimeMillis();
        bg.post(() -> doPing(ctx));
    }

    private void doPing(Context ctx) {
        if (!pingRunning) return;

        if (System.currentTimeMillis() - pingStartMs > PING_TIMEOUT_MS) {
            pingRunning = false;
            monitorRunning = false;
            prefs(ctx).edit()
                    .putBoolean(KEY_ENABLED, false)
                    .putBoolean(KEY_AUTO_RECOVERY, false)
                    .commit();
            state = State.DISABLED;
            return;
        }

        boolean ok = false;
        try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
            ok = t.exec("ping -I " + STARLINK_IF + " -c 1 -W 2 8.8.8.8").ok();
        } catch (Throwable ignored) {}

        if (!pingRunning) return;

        if (ok) {
            if (!execApply()) {
                // Apply could not be verified — do not claim ACTIVE.
                // Retry the ping/apply cycle (bounded by the 10-min timeout above).
                if (!pingRunning) return;
                bg.postDelayed(() -> doPing(ctx), PING_INTERVAL_MS);
                return;
            }
            if (!pingRunning) return;
            state = State.ACTIVE;
            if (prefs(ctx).getBoolean(KEY_AUTO_RECOVERY, false)) {
                startMonitor(ctx);
            }
        } else {
            bg.postDelayed(() -> doPing(ctx), PING_INTERVAL_MS);
        }
    }

    private void startMonitor(Context ctx) {
        if (monitorRunning) return;
        monitorRunning = true;
        consecutiveFails = 0;
        bg.post(() -> doMonitor(ctx));
    }

    private void stopMonitor() {
        monitorRunning = false;
    }

    private void doMonitor(Context ctx) {
        if (!monitorRunning) return;
        if (state != State.ACTIVE) {
            monitorRunning = false;
            return;
        }

        boolean ok = false;
        try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
            ok = t.exec("ping -I " + STARLINK_IF + " -c 1 -W 2 8.8.8.8").ok();
        } catch (Throwable ignored) {}

        if (!monitorRunning) return;

        if (ok) {
            consecutiveFails = 0;
            bg.postDelayed(() -> doMonitor(ctx), PING_INTERVAL_MS);
        } else {
            consecutiveFails++;
            if (consecutiveFails < RECOVERY_FAIL_THRESHOLD) {
                bg.postDelayed(() -> doMonitor(ctx), PING_INTERVAL_MS);
            } else {
                recover(ctx);
            }
        }
    }

    private void recover(Context ctx) {
        monitorRunning = false;
        consecutiveFails = 0;
        state = State.STARTING;
        execPurge();
        bg.postDelayed(() -> {
            // Manual disable / timeout during the post-purge wait persists
            // KEY_ENABLED=false; honor it so recovery never overrides a user OFF.
            if (!prefs(ctx).getBoolean(KEY_ENABLED, false)) return;
            pingRunning = false;
            startPingLoop(ctx);
        }, PING_INTERVAL_MS);
    }

    // Returns true only when the command's verification clause confirms the
    // intended end-state. Retries with backoff; catches Throwable. Runs on bg.
    // cancelOnDisable: bail between attempts if the router was disabled
    // (pingRunning cleared) — used by apply so a manual disable isn't blocked.
    private boolean execVerified(String command, int attempts, long backoffMs, boolean cancelOnDisable) {
        for (int i = 0; i < attempts; i++) {
            if (cancelOnDisable && !pingRunning) return false;
            try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
                if (t.exec(command).ok()) return true;
            } catch (Throwable ignored) {}
            if (i < attempts - 1) sleep(backoffMs);
        }
        return false;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean execApply() {
        return execVerified(applyCmd(), APPLY_ATTEMPTS, VERIFY_BACKOFF_MS, true);
    }

    private boolean execPurge() {
        return execVerified(purgeCmd(), PURGE_ATTEMPTS, VERIFY_BACKOFF_MS, false);
    }

    private static String applyCmd() {
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

    private static String purgeCmd() {
        return "while ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "'; do ip rule del from all iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + " priority " + RULE_PRIO + " 2>/dev/null || break; done; "
            + "while iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null; do iptables -t nat -D POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null || break; done; "
            + "while iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null || break; done; "
            + "while iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null; do iptables -D FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null || break; done; "
            + "! ip rule | grep -q 'iif " + HOTSPOT_IF + " lookup " + STARLINK_TABLE + "' "
            + "&& ! iptables -t nat -C POSTROUTING -o " + STARLINK_IF + " -j MASQUERADE 2>/dev/null "
            + "&& ! iptables -C FORWARD -i " + HOTSPOT_IF + " -o " + STARLINK_IF + " -j ACCEPT 2>/dev/null "
            + "&& ! iptables -C FORWARD -i " + STARLINK_IF + " -o " + HOTSPOT_IF + " -m state --state RELATED,ESTABLISHED -j ACCEPT 2>/dev/null";
    }

    private SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
