package com.moveinsync.roster.core;

/**
 * The ordering and idempotency rule. Each employee stores the version of the last HR change applied to them;
 * an incoming change is applied only if its version is higher.
 */
public final class VersionGate {

    private VersionGate() {
    }

    public static ApplyOutcome decide(long storedVersion, long incomingVersion) {
        if (incomingVersion > storedVersion) {
            return ApplyOutcome.APPLIED;
        }
        if (incomingVersion == storedVersion) {
            return ApplyOutcome.DUPLICATE;
        }
        return ApplyOutcome.STALE;
    }
}
