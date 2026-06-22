package com.castilhoduarte.jlh6;

public interface StateStore {
    boolean isEnabled();
    void setEnabled(boolean v);
    boolean isAutoRecovery();
    void setAutoRecovery(boolean v);
}
