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

  // --- toolbox ------------------------------------------------------------------

  /** A file a code run produced: today, a chart as a PNG. */
  public record Artifact(String type, String name, byte[] data) {}

  /** The outcome of runCode. A non-zero exit code is not an error. */
  public record CodeResult(int exitCode, String stdout, String stderr, String language, String image,
      List<Artifact> artifacts, long durationMs, boolean timedOut, boolean truncated) {
    public boolean ok() { return exitCode == 0 && !timedOut; }
  }

  /** The outcome of one command in a session. {@code cwd} is where the shell is now. */
  public record ShellResult(int exitCode, String stdout, String stderr, String cwd, long durationMs, boolean timedOut,
      boolean truncated) {
    public boolean ok() { return exitCode == 0 && !timedOut; }
  }

  /**
   * The outcome of a git operation. A git failure is {@code ok() == false}, not an exception.
   * {@code status}, {@code log} and {@code branches} are filled for those ops, otherwise null.
   */
  public record GitResult(String op, boolean ok, int exitCode, String stdout, String stderr, String path,
      Map<String, Object> status, List<Map<String, Object>> log, List<Map<String, Object>> branches) {}

  /** A file or folder in the sandbox. {@code type} is file, dir, symlink or other; {@code link} a symlink's target. */
  public record FileEntry(String name, String path, String type, long size, int mode, String modTime, String link) {
    public boolean isDir() { return "dir".equals(type); }
  }

  /** A shell session. */
  public record Session(String name, String id, String image, String status) {}

  /** A signed preview link to a port in the sandbox. {@code expiresAt} is RFC 3339. */
  public record Preview(String url, String token, String expiresAt, String container, int port, String listen) {}

  /** A template: an image built from a Dockerfile that new sandboxes can start from. */
  public record Template(String name, String image, long sizeBytes, String created, String dockerfile) {}
}
