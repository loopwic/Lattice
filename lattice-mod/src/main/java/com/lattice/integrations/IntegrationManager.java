package com.lattice.integrations;

import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.events.EventQueue;

public final class IntegrationManager {
    private static boolean rs2Enabled;

    private IntegrationManager() {
    }

    public static void initialize(LatticeConfig config, EventQueue queue) {
        rs2Enabled = Lattice.isModLoaded("refinedstorage") || Lattice.isModLoaded("refinedstorage2");
        if (rs2Enabled) {
            Rs2Integration.initialize(queue);
        }
    }

    public static boolean isRs2Enabled() {
        return rs2Enabled;
    }

    public static String summary() {
        return "rs2=" + rs2Enabled;
    }
}
