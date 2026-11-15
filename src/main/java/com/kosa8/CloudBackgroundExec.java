package com.kosa8;

import java.util.Map;

/**
 * A command started with {@link CloudSandbox#execBackground}: state is running or exited; exitCode,
 * pid, output (its tail so far) and outputBytes are null until known. outputPath holds the full
 * output in the sandbox.
 */
public record CloudBackgroundExec(String id, String sandboxId, String state, Integer pid, Integer exitCode,
    String outputPath, String exitPath, String output, Long outputBytes, boolean outputTruncated) {

  /** Whether the command has exited. */
  public boolean done() { return "exited".equals(state) || exitCode != null; }

  static CloudBackgroundExec of(Map<String, Object> m) {
    Long pid = CloudSandbox.l(m.get("pid"));
    Long exit = CloudSandbox.l(m.get("exit"));
    return new CloudBackgroundExec(CloudSandbox.s(m.get("id")), CloudSandbox.s(m.get("sandbox_id")), CloudSandbox.s(m.get("state")),
        pid == null ? null : pid.intValue(), exit == null ? null : exit.intValue(), CloudSandbox.s(m.get("output_path")),
        CloudSandbox.s(m.get("exit_path")), CloudSandbox.s(m.get("output")), CloudSandbox.l(m.get("output_bytes")),
        Boolean.TRUE.equals(m.get("output_truncated")));
  }
}
