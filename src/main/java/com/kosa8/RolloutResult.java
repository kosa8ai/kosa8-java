package com.kosa8;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One rollout of {@link Run#map}. masked means: leave it out of the gradient (the failure was not the policy's);
 * then reward is null, never 0. failureClass is ok, exit_nonzero (the grader ran and failed), timeout, infra,
 * quota, client, or error (your solve threw).
 */
public final class RolloutResult {
  public final Object task;
  /** Which task (its position in tasks) and which sample of its group. */
  public final int group;
  public final int index;
  public final Integer step;
  public Double reward;
  public String failureClass = "ok";
  public boolean masked;
  public Map<String, Object> metrics = new LinkedHashMap<>();
  /** What solve returned. */
  public Object output;
  public Integer exitCode;
  public String stdout = "";
  public String stderr = "";
  public String error;
  public int attempts;
  public long durationMs;
  public String sandboxId;
  public String graderId;
  public Long tokens;
  public Double costUsd;
  /** Whether kosa8 Cloud already recorded it (the sealed grader does, given a run). */
  public boolean recorded;
  public final Map<String, Object> meta = new LinkedHashMap<>();

  public RolloutResult(Object task, int group, int index, Integer step) {
    this.task = task;
    this.group = group;
    this.index = index;
    this.step = step;
  }

  /** Whether it trains: graded (ok or exit_nonzero) and not masked. */
  public boolean ok() { return ("ok".equals(failureClass) || "exit_nonzero".equals(failureClass)) && !masked; }

  /** The row POST /v1/rl/runs/{id}/rollouts takes. */
  public Map<String, Object> toRow() {
    Map<String, Object> m = new LinkedHashMap<>(meta);
    if (!metrics.isEmpty()) m.put("metrics", metrics);
    if (masked) m.put("masked", true);
    if (error != null) m.put("error", error.length() > 2000 ? error.substring(0, 2000) : error);
    if (attempts > 1) m.put("attempts", attempts);
    // The server keeps the classes it knows; the SDK's own "error" (solve threw) is recorded as client.
    String fc = "error".equals(failureClass) ? "client" : failureClass;
    if ("error".equals(failureClass)) m.put("sdk_failure_class", "error");
    Map<String, Object> row = new LinkedHashMap<>();
    if (step != null) row.put("step", step);
    row.put("group", String.valueOf(group));
    row.put("index", index);
    if (sandboxId != null) row.put("sandbox_id", sandboxId);
    if (reward != null) row.put("reward", reward);
    row.put("failure_class", fc);
    if (exitCode != null) row.put("exit", exitCode);
    row.put("duration_ms", durationMs);
    if (!m.isEmpty()) row.put("meta", m);
    String out = stdout == null ? "" : stdout;
    if (!out.isEmpty()) row.put("stdout_tail", out.length() > 4096 ? out.substring(out.length() - 4096) : out);
    if (tokens != null) row.put("tokens", tokens);
    if (costUsd != null) row.put("cost_usd", costUsd);
    return row;
  }

  @Override
  public String toString() {
    return "RolloutResult(group=" + group + ", index=" + index + ", reward=" + reward + ", failureClass=" + failureClass + ", masked=" + masked + ")";
  }
}
