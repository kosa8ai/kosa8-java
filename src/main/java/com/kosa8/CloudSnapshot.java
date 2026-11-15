package com.kosa8;

import java.util.Map;

/**
 * A durable snapshot of a sandbox (memory, processes and disk), kept until deleted whatever happens to
 * the sandbox. Start a sandbox from it with {@code new CloudOptions.Create().snapshot(id)}. createdAt is
 * Unix ms; info is the whole answer.
 */
public record CloudSnapshot(String id, String name, String state, String sourceSandboxId, String template, Long bytes,
    String region, Long createdAt, String lastError, Map<String, Object> info) {

  static CloudSnapshot of(Map<String, Object> m) {
    return new CloudSnapshot(CloudSandbox.s(m.get("id")), CloudSandbox.s(m.get("name")), CloudSandbox.s(m.get("state")),
        CloudSandbox.s(m.get("source_sandbox_id")), CloudSandbox.s(m.get("template")), CloudSandbox.l(m.get("bytes")),
        CloudSandbox.s(m.get("region")), CloudSandbox.l(m.get("created_at")), CloudSandbox.s(m.get("last_error")), m);
  }
}
