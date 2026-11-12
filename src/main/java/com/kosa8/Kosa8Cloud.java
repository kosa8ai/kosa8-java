package com.kosa8;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * kosa8 Cloud: the same sandboxes, run on kosa8's hosts (or your own, with bring-your-own-cloud)
 * instead of the local daemon. It matches {@code Kosa8Cloud} in the Python and TypeScript SDKs.
 *
 * <pre>{@code
 * Kosa8Cloud cloud = Kosa8Cloud.builder().build();   // KOSA8_API_KEY from the environment
 * CloudSandbox box = cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(3600));
 * System.out.print(box.exec("echo hi > /workspace/a.txt && cat /workspace/a.txt").stdout()); // hi
 * box.delete();
 * }</pre>
 *
 * <p>Authentication is an API key ({@code k8c_live_...}) made in the cloud dashboard. Errors are the
 * SDK's usual {@link Kosa8Exception}s; each carries the cloud's stable {@link Kosa8Exception#code()}
 * (for example {@code payment_required}, {@code quota_exceeded}); see
 * {@link Kosa8Exception.PaymentRequiredException}, {@link Kosa8Exception.OrgFrozenException},
 * {@link Kosa8Exception.RateLimitException} and {@link Kosa8Exception.ExecTimeoutException}.
 */
public final class Kosa8Cloud {
  public static final String DEFAULT_CLOUD_URL = "https://api.kosa8.com";
  public static final String KEY_PREFIX = "k8c_live_";

  private final String key;
  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;
  private final Sandboxes sandboxes = new Sandboxes();
  private final Functions functions = new Functions();

  private Kosa8Cloud(String key, String baseUrl, Duration timeout) {
    this.key = key;
    this.baseUrl = baseUrl;
    this.timeout = timeout;
    // Plain http is loopback only (a local control plane): skip the h2c upgrade dance there.
    HttpClient.Builder hb = HttpClient.newBuilder().connectTimeout(timeout);
    if (baseUrl.startsWith("http:")) hb.version(HttpClient.Version.HTTP_1_1);
    this.http = hb.build();
  }

  public static Builder builder() { return new Builder(); }

  /** Configures a cloud client. */
  public static final class Builder {
    private String apiKey;
    private String baseUrl;
    private Duration timeout = Duration.ofSeconds(120);
    private Function<String, String> getenv = System::getenv;

    /** Default $KOSA8_API_KEY. */
    public Builder apiKey(String v) { apiKey = v; return this; }
    /** Default $KOSA8_CLOUD_URL, then https://api.kosa8.com. */
    public Builder baseUrl(String v) { baseUrl = v; return this; }
    /** Per request; default 120 s. */
    public Builder timeout(Duration v) { timeout = v; return this; }
    /** For tests. */
    public Builder getenv(Function<String, String> v) { getenv = v; return this; }

    public Kosa8Cloud build() {
      String k = apiKey != null ? apiKey : getenv.apply("KOSA8_API_KEY");
      k = k == null ? "" : k.strip();
      if (k.isEmpty()) throw new Kosa8Exception("no kosa8 Cloud API key: pass apiKey or set KOSA8_API_KEY");
      if (!k.startsWith(KEY_PREFIX)) throw new Kosa8Exception("a kosa8 Cloud API key starts with " + KEY_PREFIX);
      String u = baseUrl;
      if (u == null || u.isEmpty()) u = getenv.apply("KOSA8_CLOUD_URL");
      if (u == null || u.isEmpty()) u = DEFAULT_CLOUD_URL;
      if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new Kosa8Exception("timeout must be positive");
      return new Kosa8Cloud(k, checkBase(u), timeout);
    }
  }

  static String checkBase(String url) {
    URI u;
    try {
      u = new URI(url);
    } catch (Exception e) {
      throw new Kosa8Exception("kosa8 Cloud address is not a URL: " + url);
    }
    if (u.getScheme() == null || u.getHost() == null) throw new Kosa8Exception("kosa8 Cloud address is not a URL: " + url);
    String h = u.getHost();
    boolean local = h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1") || h.equals("[::1]");
    String scheme = u.getScheme().toLowerCase(java.util.Locale.ROOT);
    // The API key travels in every request.
    if (!scheme.equals("https") && !(scheme.equals("http") && local)) {
      throw new Kosa8Exception("kosa8 Cloud address must use https: " + url);
    }
    return url.replaceAll("/+$", "");
  }

  /** The control-plane address in use. */
  public String baseUrl() { return baseUrl; }

  public Sandboxes sandboxes() { return sandboxes; }

  public Functions functions() { return functions; }

  /** Never the key. */
  @Override
  public String toString() { return "Kosa8Cloud(baseUrl=" + baseUrl + ")"; }

  /** The org, the key in use and, for a member's key, the member. */
  public Map<String, Object> me() { return obj(request("GET", "/v1/me", null)); }

  /** This month's usage, bill estimate and whether work is paused for payment. */
  public Map<String, Object> usage() { return obj(request("GET", "/v1/usage", null)); }

  /** The cloud's templates the org can create from now: key, cpus, mem_mb, image, regions, warm, own_host. */
  public List<Map<String, Object>> templates() { return objs(obj(request("GET", "/v1/templates", null)).get("templates")); }

  /** About how long a buffered exec may run before kosa8 Cloud cuts it off, in seconds. */
  public static final int EXEC_LIMIT_S = 30;

  static final String EXEC_HINT = "a buffered exec is limited to about " + EXEC_LIMIT_S
      + " s; run long work in the background, writing to /workspace, and poll it";

  Object request(String method, String path, Object body) {
    return send(method, path, body, null, false);
  }

  /** The answer's raw bytes (a file download). */
  byte[] requestBytes(String method, String path) {
    return (byte[]) send(method, path, null, null, true);
  }

  /** Sends raw bytes as application/octet-stream (a file upload); the answer is JSON. */
  Object requestUpload(String method, String path, byte[] data) {
    return send(method, path, null, data, false);
  }

  private Object send(String method, String path, Object body, byte[] raw, boolean rawAnswer) {
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
        .header("Authorization", "Bearer " + key).header("User-Agent", "kosa8-java/" + Kosa8Client.VERSION)
        .header("Accept", rawAnswer ? "application/octet-stream" : "application/json");
    if (raw != null) {
      rb.header("Content-Type", "application/octet-stream");
      rb.method(method, HttpRequest.BodyPublishers.ofByteArray(raw));
    } else if (body != null) {
      rb.header("Content-Type", "application/json");
      rb.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    } else {
      rb.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<byte[]> resp;
    try {
      resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (HttpTimeoutException e) {
      String m = "kosa8 Cloud did not answer within " + timeout.toMillis() / 1000.0 + " s";
      if (isExecPath(path)) {
        throw new Kosa8Exception.ExecTimeoutException(m + " (" + EXEC_HINT + ")", 0, null, null, method, path, null)
            .withCode("exec_timeout");
      }
      throw new Kosa8Exception.TimeoutException(m, method, path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Kosa8Exception.ConnectionException("interrupted talking to kosa8 Cloud", method, path, e);
    } catch (IOException e) {
      String why = e.getMessage() != null ? e.getMessage() : (e instanceof ConnectException ? "connection refused" : e.toString());
      throw new Kosa8Exception.ConnectionException("could not reach kosa8 Cloud at " + baseUrl + ": " + why, method, path, e);
    }
    byte[] bytes = resp.body() == null ? new byte[0] : resp.body();
    if (rawAnswer && resp.statusCode() < 400) return bytes;
    String text = new String(bytes, StandardCharsets.UTF_8);
    Object parsed;
    try {
      parsed = text.isEmpty() ? null : Json.parse(text);
    } catch (RuntimeException e) {
      parsed = text;
    }
    if (resp.statusCode() >= 400) {
      throw cloudError(resp.statusCode(), parsed, method, path, resp.headers().firstValue("Retry-After").orElse(null));
    }
    return parsed;
  }

  static boolean isExecPath(String path) {
    int q = path.indexOf('?');
    return (q < 0 ? path : path.substring(0, q)).endsWith("/exec");
  }

  static Kosa8Exception cloudError(int status, Object body, String method, String path) {
    return cloudError(status, body, method, path, null);
  }

  /** The cloud answers {error: code, message}: the sentence is the message, and the code is kept. */
  static Kosa8Exception cloudError(int status, Object body, String method, String path, String retryAfter) {
    String code = null;
    Object msgBody = body;
    if (body instanceof Map<?, ?> m) {
      if (m.get("error") instanceof String s) code = s;
      Object msg = m.get("message") instanceof String s && !s.isEmpty() ? s : m.get("error");
      Map<String, Object> mb = new LinkedHashMap<>();
      mb.put("error", msg);
      msgBody = mb;
    }
    String message = msgBody instanceof Map<?, ?> mm ? (mm.get("error") == null ? "" : String.valueOf(mm.get("error")))
        : (msgBody instanceof String ms ? ms.strip() : "");
    boolean execTimeout = status == 504 || "exec_timeout".equals(code)
        // Today the Worker gives up on the host after about 30 s: 502 host_failed "... timed out".
        || (status == 502 && isExecPath(path) && message.toLowerCase(java.util.Locale.ROOT).contains("timed out"));
    if (execTimeout) {
      String base = message.isEmpty() ? "request failed with HTTP " + status : message;
      return new Kosa8Exception.ExecTimeoutException(base + " (" + EXEC_HINT + ")", status, message.isEmpty() ? null : message,
          null, method, path, body).withCode(code != null ? code : "exec_timeout");
    }
    Kosa8Exception e = Kosa8Exception.fromResponse(status, msgBody, body, method, path).withCode(code);
    if (e instanceof Kosa8Exception.RateLimitException rl) rl.withRetryAfter(parseRetryAfter(retryAfter));
    return e;
  }

  /** Retry-After is seconds or an HTTP date; null when absent or unreadable. */
  static Duration parseRetryAfter(String v) {
    if (v == null || v.isBlank()) return null;
    String t = v.strip();
    try {
      double secs = Double.parseDouble(t);
      if (secs < 0 || Double.isNaN(secs) || Double.isInfinite(secs)) return null;
      return Duration.ofMillis((long) (secs * 1000));
    } catch (NumberFormatException ignored) {
      // an HTTP date
    }
    try {
      java.time.ZonedDateTime at = java.time.ZonedDateTime.parse(t, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
      Duration d = Duration.between(java.time.Instant.now(), at.toInstant());
      return d.isNegative() ? Duration.ZERO : d;
    } catch (java.time.format.DateTimeParseException ignored) {
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> obj(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  static List<Map<String, Object>> objs(Object o) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
    return out;
  }

  /** {@code cloud.sandboxes()}: create, list and look up sandboxes. */
  public final class Sandboxes {
    private Sandboxes() {}

    /** Start a sandbox with the org's default template, region and sleep setting. */
    public CloudSandbox create() { return create(null, null, null); }

    /** Start a sandbox from a template (base, python, node, ...). */
    public CloudSandbox create(String template) { return create(template, null, null); }

    /**
     * Start a sandbox; returns once it is running. A null argument takes the org's setting;
     * sleepAfterS is idle seconds before it sleeps (0 never).
     */
    public CloudSandbox create(String template, String region, Integer sleepAfterS) {
      Map<String, Object> b = new LinkedHashMap<>();
      if (template != null) b.put("template", template);
      if (region != null) b.put("region", region);
      if (sleepAfterS != null) b.put("sleep_after_s", sleepAfterS);
      return new CloudSandbox(Kosa8Cloud.this, obj(obj(request("POST", "/v1/sandboxes", b)).get("sandbox")));
    }

    /**
     * Start a sandbox with a lifetime, Store price and the rest:
     * {@code create(new CloudOptions.Create().template("python").ttlSeconds(3600))}. Only the fields
     * set are sent; a bad ttl (outside 60 s to 90 days), or both ttlSeconds and deleteAfter, throws
     * IllegalArgumentException before any request.
     */
    public CloudSandbox create(CloudOptions.Create options) {
      Map<String, Object> b = (options == null ? new CloudOptions.Create() : options).body();
      return new CloudSandbox(Kosa8Cloud.this, obj(obj(request("POST", "/v1/sandboxes", b)).get("sandbox")));
    }

    /** The org's live sandboxes. */
    public List<CloudSandbox> list() { return list(false); }

    /** The org's sandboxes; with all, deleted ones too. */
    public List<CloudSandbox> list(boolean all) { return listPage(new CloudOptions.List().all(all)).sandboxes(); }

    /** One page of sandboxes (with a limit or cursor), or every one from a server that does not page. */
    public List<CloudSandbox> list(CloudOptions.List options) { return listPage(options).sandboxes(); }

    /** The first page of live sandboxes. */
    public CloudSandboxPage listPage() { return listPage(null); }

    /**
     * One page of sandboxes and the cursor of the next (null at the end). Today's server ignores
     * limit and cursor and answers everything as one page.
     */
    public CloudSandboxPage listPage(CloudOptions.List options) {
      CloudOptions.List o = options == null ? new CloudOptions.List() : options;
      Map<String, Object> r = obj(request("GET", "/v1/sandboxes" + o.query(), null));
      List<CloudSandbox> out = new ArrayList<>();
      for (Map<String, Object> s : objs(r.get("sandboxes"))) out.add(new CloudSandbox(Kosa8Cloud.this, s));
      String next = r.get("next_cursor") instanceof String n && !n.isEmpty() ? n : null;
      return new CloudSandboxPage(out, next);
    }

    /** Every live sandbox, following next_cursor page by page. */
    public List<CloudSandbox> listAll() { return listAll(null); }

    /**
     * Every sandbox, following next_cursor page by page from options' cursor (limit is the page
     * size). Stops when a cursor comes back a second time, so a looping server cannot hang it.
     */
    public List<CloudSandbox> listAll(CloudOptions.List options) {
      CloudOptions.List o = options == null ? new CloudOptions.List() : options.copy();
      List<CloudSandbox> out = new ArrayList<>();
      java.util.Set<String> seen = new java.util.HashSet<>();
      while (true) {
        CloudSandboxPage page = listPage(o);
        out.addAll(page.sandboxes());
        String next = page.nextCursor();
        if (next == null || !seen.add(next)) return out;
        o.cursor(next);
      }
    }

    public CloudSandbox get(String id) {
      return new CloudSandbox(Kosa8Cloud.this, obj(obj(request("GET", "/v1/sandboxes/" + Transport.enc(id), null)).get("sandbox")));
    }
  }

  /** {@code cloud.functions()}: deploy and call functions. */
  public final class Functions {
    private Functions() {}

    private String p(String name, String suffix) { return "/v1/functions/" + Transport.enc(name) + suffix; }

    /**
     * Deploy (create or replace) a function; every deploy bumps its version. The spec holds
     * runtime (python3.12, node22), code, and optionally env, timeout_s, web, schedule.
     */
    public Map<String, Object> deploy(String name, Map<String, ?> spec) { return obj(request("PUT", p(name, ""), spec)); }

    public List<Map<String, Object>> list() { return objs(obj(request("GET", "/v1/functions", null)).get("functions")); }

    public Map<String, Object> get(String name) { return obj(request("GET", p(name, ""), null)); }

    public void delete(String name) { request("DELETE", p(name, ""), null); }

    /** Call it once with a null event. */
    public Map<String, Object> invoke(String name) { return invoke(name, null); }

    /** Call it once. A handler that raises answers ok=false, not an exception. */
    public Map<String, Object> invoke(String name, Object event) {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("event", event);
      return obj(request("POST", p(name, "/invoke"), b));
    }

    /** One call per event, results in input order. */
    public Map<String, Object> map(String name, List<?> events) { return map(name, events, null); }

    /** One call per event, at most concurrency at once (null: the cloud's default); results in input order. */
    public Map<String, Object> map(String name, List<?> events, Integer concurrency) {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("events", events);
      if (concurrency != null) b.put("concurrency", concurrency);
      return obj(request("POST", p(name, "/map"), b));
    }
  }
}
