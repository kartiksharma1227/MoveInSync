package com.moveinsync.roster.core;

/** What happened when an HR change was offered to an employee. */
public enum ApplyOutcome {
    /** Newer version: the change was applied. */
    APPLIED,
    /** Same version and same data: a replay, nothing to do. */
    DUPLICATE,
    /** Older version: a newer change is already applied, so this one is ignored. */
    STALE,
    /** Same version but different data: the source broke its own contract. Ignored, but reported. */
    VERSION_CONFLICT
}
