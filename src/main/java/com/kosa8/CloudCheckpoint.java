package com.kosa8;

import java.util.Map;

/**
 * A kosa8 Cloud sandbox checkpoint: memory, processes and disk. auto is true for one saved
 * automatically before a rewind; createdAt is the host's timestamp text (may be null); bytes its size.
 */
public record CloudCheckpoint(String id, String label, boolean auto, String createdAt, Long bytes) {
  static CloudCheckpoint of(Map<String, Object> m) {
    return new CloudCheckpoint(CloudSandbox.s(m.get("id")), CloudSandbox.s(m.get("label")), Boolean.TRUE.equals(m.get("auto")),
        m.get("created_at") == null ? null : String.valueOf(m.get("created_at")), CloudSandbox.l(m.get("bytes")));
  }
}
