package com.kosa8;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retries with exponential backoff and full jitter.
 *
 * <p>{@code attempts} counts the first try ({@link #NONE}, 1, turns retries off). The n-th retry waits a random
 * time up to {@code min(max, base * 2^(n-1))} (full jitter, so a thousand rollouts that failed together do not
 * come back together), and never less than the server's Retry-After (capped at {@code maxRetryAfter}). Only
 * failures that can pass are retried ({@link Kosa8Exception#retryable()}): infra failures and rate limits.
 */
public record RetryPolicy(int attempts, Duration base, Duration max, Duration maxRetryAfter) {
  /** 3 tries, 0.5 s base, 8 s cap, Retry-After honoured up to 60 s. */
  public static final RetryPolicy DEFAULT = new RetryPolicy(3, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(60));
  /** No retries. */
  public static final RetryPolicy NONE = new RetryPolicy(1, Duration.ofMillis(500), Duration.ofSeconds(8), Duration.ofSeconds(60));

  public RetryPolicy {
    if (attempts < 1) throw new IllegalArgumentException("attempts must be at least 1");
    if (base == null || max == null || maxRetryAfter == null || base.isNegative() || max.isNegative() || maxRetryAfter.isNegative()) {
      throw new IllegalArgumentException("base, max and maxRetryAfter must be durations of 0 or more");
    }
  }

  /** The default backoff with this many attempts. */
  public static RetryPolicy attempts(int attempts) { return new RetryPolicy(attempts, DEFAULT.base, DEFAULT.max, DEFAULT.maxRetryAfter); }

  /** Whether to try again after t on attempt (1-based). */
  public boolean shouldRetry(Throwable t, int attempt) {
    return attempt < attempts && Kosa8Exception.classifyFailure(t).retryable();
  }

  /** How long to wait before attempt + 1. */
  public Duration delay(Throwable t, int attempt) {
    long capMs = Math.min(max.toMillis(), base.toMillis() * (1L << Math.min(30, Math.max(0, attempt - 1))));
    long wait = capMs > 0 ? ThreadLocalRandom.current().nextLong(capMs + 1) : 0;
    if (t instanceof Kosa8Exception k && k.retryAfterSeconds() != null && k.retryAfterSeconds() > 0) {
      wait = Math.max(wait, Math.min((long) (k.retryAfterSeconds() * 1000), maxRetryAfter.toMillis()));
    }
    return Duration.ofMillis(wait);
  }
}
