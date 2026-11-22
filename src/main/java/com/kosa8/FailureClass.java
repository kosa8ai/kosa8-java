package com.kosa8;

import java.util.Set;

/**
 * Whose fault a failure was, as kosa8 Cloud says on every answer under /v1/sandboxes: {@code ok},
 * {@code exit_nonzero} (the command ran and failed: your code), {@code timeout}, {@code infra} (a host failed or
 * had no room: retryable), {@code quota} (a plan, payment or running limit; a rate limit is retryable) or
 * {@code client} (the request was wrong). The SDK adds {@code error}: your own code threw.
 *
 * <p>A trainer masks infra and quota rollouts instead of scoring them as reward 0.
 */
public record FailureClass(String failureClass, boolean retryable) {
  /** The classes kosa8 Cloud sends. */
  public static final java.util.List<String> CLASSES = java.util.List.of("ok", "exit_nonzero", "timeout", "oom", "infra", "quota", "client");

  private static final Set<String> INFRA = Set.of("host_failed", "not_ready", "no_capacity", "host_unreachable", "host_unavailable",
      "host_busy", "host_restarting", "wake_failed", "try_again", "internal", "kernel_failed", "egress_failed", "dns_unreachable",
      "host_lost", "host_removed", "insufficient_storage", "starting");
  private static final Set<String> RATE = Set.of("rate_limited", "slow_down", "too_soon");
  private static final Set<String> QUOTA = Set.of("quota_exceeded", "running_limit", "plan_required", "plan_limit", "plan_feature",
      "payment_required", "payment_declined", "spending_cap_reached", "snapshot_limit", "org_frozen", "org_suspended", "too_many_runs");

  /** The server's failureOf: the class of an HTTP status and error code (code may be null). */
  public static FailureClass of(int status, String code) {
    String c = code == null ? "" : code;
    if (status < 400) return new FailureClass("ok", false);
    if (c.equals("exec_timeout")) return new FailureClass("timeout", false);
    if (c.contains("timeout")) return new FailureClass("timeout", true);
    if (RATE.contains(c)) return new FailureClass("quota", true);
    if (QUOTA.contains(c) || status == 402 || status == 423) return new FailureClass("quota", false);
    if (status == 429) return new FailureClass("quota", !c.equals("quota_exceeded"));
    if (c.equals("not_available") || c.equals("not_supported") || status == 501) return new FailureClass("infra", false);
    if (INFRA.contains(c) || status >= 500) return new FailureClass("infra", true);
    return new FailureClass("client", false);
  }

  /** The class of an exec that ran: ok on exit 0, exit_nonzero on any other, infra (retryable) with no exit code. */
  public static FailureClass ofExit(Integer exit) {
    if (exit == null) return new FailureClass("infra", true);
    return new FailureClass(exit == 0 ? "ok" : "exit_nonzero", false);
  }
}
