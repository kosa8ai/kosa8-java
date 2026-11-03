package com.kosa8;

import java.time.Instant;

/** One restore point: a snapshot with the id {@code <sandbox>-ckpt-<n>}. */
public record Checkpoint(String id, String sandbox, String label, boolean auto, Instant created) {}
