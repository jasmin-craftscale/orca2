package com.lynxis.orca.runtime.execution.engine;

/** Lifecycle of an engine instance as the seam exposes it. */
public enum InstanceState {
    RUNNING,
    WAITING,
    COMPLETED,
    CANCELLED
}
