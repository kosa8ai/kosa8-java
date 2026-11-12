package com.kosa8;

/**
 * A finished rewind: the checkpoint gone back to, and backup, the sandbox as it was just before
 * (saved automatically, so the rewind can itself be undone). ms is how long it took, when known.
 */
public record CloudRewindResult(CloudCheckpoint checkpoint, CloudCheckpoint backup, Long ms) {}
