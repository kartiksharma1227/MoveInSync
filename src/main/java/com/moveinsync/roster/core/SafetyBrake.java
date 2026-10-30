package com.moveinsync.roster.core;

/**
 * Guards the nightly run against broken exports. If too many currently active employees are missing from the
 * file, the file is almost certainly truncated, so the whole run is held for an operator instead of deactivating them.
 */
public final class SafetyBrake {

    public record Decision(boolean held, long activeCount, long missingCount, double missingRatio) {
    }

    private final double threshold;

    public SafetyBrake(double threshold) {
        if (threshold <= 0 || threshold > 1) {
            throw new IllegalArgumentException("Brake threshold must be in (0, 1], was " + threshold);
        }
        this.threshold = threshold;
    }

    public Decision evaluate(long activeCount, long missingCount) {
        // No active employees yet (first ever load): nothing can be wrongly deactivated.
        double missingRatio = activeCount == 0 ? 0.0 : (double) missingCount / activeCount;
        boolean held = activeCount > 0 && missingRatio >= threshold;
        return new Decision(held, activeCount, missingCount, missingRatio);
    }

    public double threshold() {
        return threshold;
    }
}
