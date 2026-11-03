package com.kosa8;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** What the client's calls return. */
public final class Results {
  private Results() {}

  /** The outcome of a run, exec or attach. */
  public record Exec(int exitCode, byte[] stdoutBytes, byte[] stderrBytes) {
    public String stdout() { return new String(stdoutBytes, StandardCharsets.UTF_8); }
    public String stderr() { return new String(stderrBytes, StandardCharsets.UTF_8); }
    public boolean ok() { return exitCode == 0; }
  }

  /** The answer to a fork. */
  public record Fork(List<Map<String, Object>> forks, Object snapshot, long forkMs, int count) {}

  /** One variant's outcome in an explore. */
  public record ExploreBranch(String name, String variant, int exit, String output, String error, long durationMs) {}

  /** What explore kept and dropped. {@code winner} is "" when no branch succeeded. */
  public record Explore(String parent, List<ExploreBranch> branches, String winner, int removed) {}

  /** What checkpoint saved. */
  public record CheckpointSaved(Checkpoint checkpoint, List<String> evicted, Map<String, Object> snapshot) {}

  /** What rewind restored. */
  public record Rewind(Object sandbox, Checkpoint checkpoint, List<String> dropped, Object restoreMs) {}
}
