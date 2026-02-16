package com.lattice.config;

import java.util.concurrent.atomic.AtomicReference;

public final class RuntimeConfigStore {
    private final AtomicReference<LatticeConfig> ref;

    public RuntimeConfigStore(LatticeConfig initial) {
        this.ref = new AtomicReference<>(initial);
    }

    public LatticeConfig current() {
        return ref.get();
    }

    public LatticeConfig swap(LatticeConfig next) {
        return ref.getAndSet(next);
    }
}
