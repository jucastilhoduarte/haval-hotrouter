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
    private static final int CONNECT_MS = 2_000;
    private static final int READ_MS = 6_000;

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
    private volatile RouterCore core;

    private RouterManager() {
        HandlerThread t = new HandlerThread("RouterManager");
        t.start();
        bg = new Handler(t.getLooper());
    }

    private RouterCore core(Context ctx) {
        if (core == null) {
            synchronized (this) {
                if (core == null) {
                    Context app = ctx.getApplicationContext();
                    core = new RouterCore(
                            System::currentTimeMillis,
                            new HandlerScheduler(bg),
                            new TelnetShell(),
                            new SharedPrefsStore(app));
                }
            }
        }
        return core;
    }

    public State getState() { return core == null ? State.DISABLED : map(core.getState()); }
    public boolean isAutoRecovery(Context ctx) { return core(ctx).isAutoRecovery(); }
    public void setAutoRecovery(Context ctx, boolean on) { core(ctx).setAutoRecovery(on); }
    public void restoreIfEnabled(Context ctx) { core(ctx).restoreIfEnabled(); }
    public void enable(Context ctx) { core(ctx).enable(); }
    public void disable(Context ctx) { core(ctx).disable(); }

    private static State map(RouterCore.State s) {
        switch (s) {
            case STARTING: return State.STARTING;
            case ACTIVE:   return State.ACTIVE;
            case PURGING:  return State.PURGING;
            default:       return State.DISABLED;
        }
    }

    private static final class HandlerScheduler implements Scheduler {
        private final Handler h;
        HandlerScheduler(Handler h) { this.h = h; }
        @Override public void post(Runnable r) { h.post(r); }
        @Override public void postDelayed(Runnable r, long delayMs) { h.postDelayed(r, delayMs); }
        @Override public void removeAll() { h.removeCallbacksAndMessages(null); }
        @Override public void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private static final class TelnetShell implements Shell {
        @Override public Shell.ShellResult exec(String command) {
            try (TelnetRoot t = new TelnetRoot(CONNECT_MS, READ_MS)) {
                TelnetRoot.Result r = t.exec(command);
                return new Shell.ShellResult(r.output, r.exitCode);
            } catch (Throwable e) {
                return new Shell.ShellResult("", -1);
            }
        }
    }

    private static final class SharedPrefsStore implements StateStore {
        private final SharedPreferences p;
        SharedPrefsStore(Context app) { p = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE); }
        @Override public boolean isEnabled() { return p.getBoolean(KEY_ENABLED, false); }
        @Override public void setEnabled(boolean v) { p.edit().putBoolean(KEY_ENABLED, v).commit(); }
        @Override public boolean isAutoRecovery() { return p.getBoolean(KEY_AUTO_RECOVERY, false); }
        @Override public void setAutoRecovery(boolean v) { p.edit().putBoolean(KEY_AUTO_RECOVERY, v).commit(); }
    }
}
