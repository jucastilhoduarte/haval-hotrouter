package com.castilhoduarte.jlh6;

public final class FakeStore implements StateStore {
    private boolean enabled, auto;
    @Override public boolean isEnabled() { return enabled; }
    @Override public void setEnabled(boolean v) { enabled = v; }
    @Override public boolean isAutoRecovery() { return auto; }
    @Override public void setAutoRecovery(boolean v) { auto = v; }
}
