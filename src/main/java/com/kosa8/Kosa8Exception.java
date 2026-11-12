package com.kosa8;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Every error this SDK throws. The daemon's own words are kept in
 * {@link #daemonMessage()} and {@link #remediation()}, as @kosa8/sdk does.
 */
public class Kosa8Exception extends RuntimeException {
  private final int status;
  private final String daemonMessage;
  private final String remediation;
  private final String method;
  private final String path;
  private final Object body;
  private String code;

  public Kosa8Exception(String message) {
    this(message, 0, null, null, null, null, null, null);
  }

  public Kosa8Exception(String message, Throwable cause) {
    this(message, 0, null, null, null, null, null, cause);
  }

  public Kosa8Exception(String message, int status, String daemonMessage, String remediation, String method,
      String path, Object body, Throwable cause) {
    super(message, cause);
    this.status = status;
    this.daemonMessage = daemonMessage;
    this.remediation = remediation;
    this.method = method;
    this.path = path;
    this.body = body;
  }

  /** HTTP status, or 0 when the daemon did not answer. */
  public int status() { return status; }
  /** The daemon's "error" text, verbatim. */
  public String daemonMessage() { return daemonMessage; }
  /** The daemon's "remediation": what to do about it. */
  public String remediation() { return remediation; }
  public String method() { return method; }
  public String path() { return path; }
  /** The decoded response body. */
  public Object body() { return body; }
  /** kosa8 Cloud's stable error code (payment_required, quota_exceeded, ...); null from the daemon. */
  public String code() { return code; }

  Kosa8Exception withCode(String c) {
    code = c;
    return this;
  }

  /** The daemon is unreachable. */
  public static class ConnectionException extends Kosa8Exception {
    public ConnectionException(String m, String method, String path, Throwable cause) {
      super(m, 0, null, null, method, path, null, cause);
    }
  }

  /** Any HTTP error answer. */
  public static class ApiException extends Kosa8Exception {
    public ApiException(String m, int st, String dm, String rem, String method, String path, Object body) {
      super(m, st, dm, rem, method, path, body, null);
    }
  }

  /** 400 */
  public static class BadRequestException extends ApiException {
    public BadRequestException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** 401 */
  public static class AuthException extends ApiException {
    public AuthException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** 403 */
  public static class PolicyException extends ApiException {
    public PolicyException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** 403 that a signed approval would lift; {@link #approval()} is the daemon's refusal. */
  public static class ApprovalRequiredException extends PolicyException {
    private final Map<String, Object> approval;

    public ApprovalRequiredException(String m, int st, String dm, String rem, String me, String p, Object b,
        Map<String, Object> approval) {
      super(m, st, dm, rem, me, p, b);
      this.approval = approval;
    }

    public Map<String, Object> approval() { return approval; }
  }

  /** 404 */
  public static class NotFoundException extends ApiException {
    public NotFoundException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /**
   * 402: kosa8 Cloud refused new work: no payment method on file ({@code payment_required}), the
   * spending cap is reached ({@code spending_cap_reached}), or a paid Store item's price was not
   * accepted ({@code price_not_accepted}); {@link #code()} says which.
   */
  public static class PaymentRequiredException extends ApiException {
    public PaymentRequiredException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }

    /** Where the person adds a payment method or credit; null when the cloud sent none. */
    public String upgradeUrl() { return bodyString(body(), "upgrade_url"); }

    /**
     * A sentence an AI agent should relay to its person word for word (and then stop, not retry);
     * null when the cloud sent none.
     */
    public String agentMessage() { return bodyString(body(), "agent_message"); }
  }

  /**
   * 423 {@code org_frozen}: the organization is frozen in the Security Center. Nothing runs until an
   * owner or admin unfreezes it there.
   */
  public static class OrgFrozenException extends ApiException {
    public OrgFrozenException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /**
   * A buffered kosa8 Cloud exec ran too long: HTTP 504 or code {@code exec_timeout}, or today's 502
   * {@code host_failed} "timed out" after about 30 s. Run long work in the background, writing to
   * /workspace, and poll it: {@code box.exec("nohup sh -c 'make test > /workspace/test.log 2>&1' &")}.
   */
  public static class ExecTimeoutException extends ApiException {
    public ExecTimeoutException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** 409 */
  public static class ConflictException extends ApiException {
    public ConflictException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** 429: too many requests, or a quota reached ({@code quota_exceeded}). */
  public static class RateLimitException extends ApiException {
    private Duration retryAfter;

    public RateLimitException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }

    /** How long the server asked to wait (its Retry-After header), when it sent one. */
    public Optional<Duration> retryAfter() { return Optional.ofNullable(retryAfter); }

    RateLimitException withRetryAfter(Duration d) {
      retryAfter = d;
      return this;
    }
  }

  /** 501: not available here (from kosa8 Cloud: {@code not_available} / {@code not_supported}, a host without the data plane). */
  public static class UnsupportedException extends ApiException {
    public UnsupportedException(String m, int st, String dm, String rem, String me, String p, Object b) { super(m, st, dm, rem, me, p, b); }
  }

  /** A stream ended with an error frame, or without a final frame; output so far is kept. */
  public static class StreamException extends Kosa8Exception {
    private final String stdout;
    private final String stderr;

    public StreamException(String m, String stdout, String stderr, String daemonMessage) {
      super(m, 0, daemonMessage, null, null, null, null, null);
      this.stdout = stdout;
      this.stderr = stderr;
    }

    public String stdout() { return stdout; }
    public String stderr() { return stderr; }
  }

  /** Client-side checkpoint bookkeeping. */
  public static class CheckpointException extends Kosa8Exception {
    public CheckpointException(String m) { super(m); }
    public CheckpointException(String m, Throwable cause) { super(m, cause); }
  }

  /** A request ran out of time. */
  public static class TimeoutException extends Kosa8Exception {
    public TimeoutException(String m, String method, String path) {
      super(m, 0, null, null, method, path, null, null);
    }
  }

  /** The typed error for an HTTP error answer: the daemon's message with its remediation in parentheses. */
  static Kosa8Exception fromResponse(int status, Object body, String method, String path) {
    return fromResponse(status, body, body, method, path);
  }

  /** As above, reading the message from msgBody while keeping body as {@link #body()}. */
  @SuppressWarnings("unchecked")
  static Kosa8Exception fromResponse(int status, Object msgBody, Object body, String method, String path) {
    Object b0 = msgBody;
    String msg = null;
    String rem = null;
    Map<String, Object> approval = null;
    if (b0 instanceof Map<?, ?> m) {
      Object e = m.get("error");
      msg = e instanceof String s && !s.isEmpty() ? s : (m.get("message") instanceof String s2 ? s2 : null);
      if (m.get("remediation") instanceof String r && !r.isEmpty()) rem = r;
      if (status == 403 && m.get("approval") instanceof Map<?, ?> a && a.get("why") instanceof String) {
        approval = (Map<String, Object>) a;
      }
    } else if (b0 instanceof String s && !s.isBlank()) {
      msg = s.strip();
    }
    String base = msg != null && !msg.isEmpty() ? msg : "request failed with HTTP " + status;
    String text = rem != null ? base + " (" + rem + ")" : base;
    if (approval != null) return new ApprovalRequiredException(text, status, msg, rem, method, path, body, approval);
    return switch (status) {
      case 400 -> new BadRequestException(text, status, msg, rem, method, path, body);
      case 401 -> new AuthException(text, status, msg, rem, method, path, body);
      case 403 -> new PolicyException(text, status, msg, rem, method, path, body);
      case 402 -> new PaymentRequiredException(text, status, msg, rem, method, path, body);
      case 404 -> new NotFoundException(text, status, msg, rem, method, path, body);
      case 409 -> new ConflictException(text, status, msg, rem, method, path, body);
      case 423 -> new OrgFrozenException(text, status, msg, rem, method, path, body);
      case 429 -> new RateLimitException(text, status, msg, rem, method, path, body);
      case 501 -> new UnsupportedException(text, status, msg, rem, method, path, body);
      default -> new ApiException(text, status, msg, rem, method, path, body);
    };
  }

  static String bodyString(Object body, String key) {
    return body instanceof Map<?, ?> m && m.get(key) instanceof String s && !s.isEmpty() ? s : null;
  }
}
