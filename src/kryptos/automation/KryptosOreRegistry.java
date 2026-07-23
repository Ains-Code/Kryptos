package kryptos.automation;

import arc.struct.IntSet;

/**
 * Shared claimed-ore-cluster registry.
 *
 * KryptosAutoConveyor and KryptosSmartDrill each scan the map for ore
 * clusters and queue drill + belt build plans independently. Before this
 * existed, they kept separate "already served" sets, so nothing stopped
 * both modules from targeting the same deposit in the same window and
 * queuing overlapping/duplicate drills. Both modules now claim a cluster
 * here before acting on it, and check here before considering one.
 */
public final class KryptosOreRegistry {

    private static final IntSet claimed = new IntSet();

    private KryptosOreRegistry() {}

    public static boolean isClaimed(int clusterKey) {
        return claimed.contains(clusterKey);
    }

    public static void claim(int clusterKey) {
        claimed.add(clusterKey);
    }

    public static int size() {
        return claimed.size;
    }

    public static void reset() {
        claimed.clear();
    }
}
