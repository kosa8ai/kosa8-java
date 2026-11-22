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
  /** A capability token from {@code cloud.tokens().create(...)} also works as the key. */
  public static final String TOKEN_PREFIX = "k8c_tok_";

  private final String key;
  private final String baseUrl;
  private final Duration timeout;
  private final HttpClient http;
  private final RetryPolicy retry;
  private final java.util.function.Consumer<Duration> sleeper;
  private final Sandboxes sandboxes = new Sandboxes();
  private final Functions functions = new Functions();
  private final Snapshots snapshots = new Snapshots();
  private final Secrets secrets = new Secrets();
  private final Pipelines pipelines = new Pipelines();
  private final OrgTemplates orgTemplates = new OrgTemplates();
  private final Tokens tokens = new Tokens();

  private Kosa8Cloud(String key, String baseUrl, Duration timeout, RetryPolicy retry, java.util.function.Consumer<Duration> sleeper) {
    this.key = key;
    this.baseUrl = baseUrl;
    this.timeout = timeout;
    this.retry = retry;
    this.sleeper = sleeper;
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
    private RetryPolicy retry = RetryPolicy.DEFAULT;
    private java.util.function.Consumer<Duration> sleeper = CloudSandbox::pause;

    /** Default $KOSA8_API_KEY. */
    public Builder apiKey(String v) { apiKey = v; return this; }
    /** Default $KOSA8_CLOUD_URL, then https://api.kosa8.com. */
    public Builder baseUrl(String v) { baseUrl = v; return this; }
    /** Per request; default 120 s. */
    public Builder timeout(Duration v) { timeout = v; return this; }
    /** For tests. */
    public Builder getenv(Function<String, String> v) { getenv = v; return this; }
    /**
     * Retries for every request where trying again is safe (default {@link RetryPolicy#DEFAULT}, 3 tries with
     * backoff and jitter): reads, PUTs, deletes, heartbeats and drains on infra failures; the calls that make
     * sandboxes (create, batch, fork, grade), which carry an Idempotency-Key reused on every retry; and any
     * request answered 429, waiting at least its Retry-After. {@link RetryPolicy#NONE} turns it off.
     */
    public Builder retry(RetryPolicy v) { retry = v == null ? RetryPolicy.NONE : v; return this; }
    /** How retries wait (default Thread.sleep); for tests. */
    public Builder sleeper(java.util.function.Consumer<Duration> v) { sleeper = v; return this; }

    public Kosa8Cloud build() {
      String k = apiKey != null ? apiKey : getenv.apply("KOSA8_API_KEY");
      k = k == null ? "" : k.strip();
      if (k.isEmpty()) throw new Kosa8Exception("no kosa8 Cloud API key: pass apiKey or set KOSA8_API_KEY");
      if (!k.startsWith(KEY_PREFIX) && !k.startsWith(TOKEN_PREFIX)) {
        throw new Kosa8Exception("a kosa8 Cloud API key starts with " + KEY_PREFIX + " (or a capability token with " + TOKEN_PREFIX + ")");
      }
      String u = baseUrl;
      if (u == null || u.isEmpty()) u = getenv.apply("KOSA8_CLOUD_URL");
      if (u == null || u.isEmpty()) u = DEFAULT_CLOUD_URL;
      if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new Kosa8Exception("timeout must be positive");
      return new Kosa8Cloud(k, checkBase(u), timeout, retry, sleeper);
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

  /** The org's durable snapshots. */
  public Snapshots snapshots() { return snapshots; }

  /** The org's secrets. */
  public Secrets secrets() { return secrets; }

  /** kosa8 Pipelines: CI runs and hooks. */
  public Pipelines pipelines() { return pipelines; }

  /** The org's own templates, built once and kept as snapshots new sandboxes restore from. */
  public OrgTemplates orgTemplates() { return orgTemplates; }

  /** Short-lived capability tokens to hand a sandbox to someone without the API key. */
  public Tokens tokens() { return tokens; }

  /** The retry policy in use. */
  public RetryPolicy retryPolicy() { return retry; }

  java.util.function.Consumer<Duration> sleeper() { return sleeper; }

  /**
   * {@code fn(child, item)} for every item, each in its own fork of base, at most {@code concurrency} at once;
   * results in item order. See {@link CloudOptions.ForkMap}.
   */
  public <T, R> List<ForkResult<T, R>> forkMap(CloudSandbox base, List<T> items, ForkMap.Fn<T, R> fn) {
    return forkMap(base, items, fn, null);
  }

  /**
   * {@code fn(child, item)} for every item, each in a fresh fork of base, at most concurrency at once; results
   * in item order. A retryable failure (infra, a rate limit) is tried again on a new fork with backoff, up to
   * retries times; what still fails is reported in its {@link ForkResult}, never thrown. Every child is deleted
   * when its item is done, and any left (an exception, an interrupt) when the map ends.
   */
  public <T, R> List<ForkResult<T, R>> forkMap(CloudSandbox base, List<T> items, ForkMap.Fn<T, R> fn, CloudOptions.ForkMap options) {
    return ForkMap.run(this, base, items, fn, options == null ? new CloudOptions.ForkMap() : options);
  }

  /** What the org and its hosts can do, for feature detection. */
  public Map<String, Object> capabilities() { return obj(request("GET", "/v1/capabilities", null)); }

  /** The named host lists an egress policy's presets can use. */
  public Map<String, Object> egressPresets() { return obj(obj(request("GET", "/v1/egress/presets", null)).get("presets")); }

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
    return request(method, path, body, null);
  }

  /** A request; a call that makes sandboxes carries idemKey (a new UUID when null), the same on every retry. */
  Object request(String method, String path, Object body, String idemKey) {
    return send(method, path, body, null, false, idemKey);
  }

  /** The answer's raw bytes (a file download). */
  byte[] requestBytes(String method, String path) {
    return (byte[]) send(method, path, null, null, true, null);
  }

  /** Sends raw bytes as application/octet-stream (a file upload); the answer is JSON. */
  Object requestUpload(String method, String path, byte[] data) {
    return send(method, path, null, data, false, null);
  }

  private static final java.util.regex.Pattern KEYED = java.util.regex.Pattern.compile("^/v1/sandboxes(/batch|/[A-Za-z0-9_]{1,64}/(fork|grade))?$");
  private static final java.util.regex.Pattern KEY_RE = java.util.regex.Pattern.compile("^[\\x21-\\x7e]{1,255}$");
  private static final java.util.Set<String> SAFE = java.util.Set.of("GET", "HEAD", "PUT", "DELETE");

  /** Whether kosa8 Cloud keeps an Idempotency-Key for this call (24 hours). */
  static boolean keyedRoute(String method, String path) {
    int q = path.indexOf('?');
    return "POST".equals(method) && KEYED.matcher(q < 0 ? path : path.substring(0, q)).matches();
  }

  static String checkIdempotencyKey(String k) {
    if (k != null && !KEY_RE.matcher(k).matches()) throw new IllegalArgumentException("an idempotency key is 1-255 printable ASCII characters, e.g. a UUID");
    return k;
  }

  private Object send(String method, String path, Object body, byte[] raw, boolean rawAnswer, String idemKey) {
    checkIdempotencyKey(idemKey);
    String idem = idemKey != null ? idemKey : keyedRoute(method, path) ? java.util.UUID.randomUUID().toString() : null;
    int q = path.indexOf('?');
    String bare = q < 0 ? path : path.substring(0, q);
    boolean safe = idem != null || SAFE.contains(method) || bare.equals("/v1/sandboxes/heartbeat") || bare.equals("/v1/sandboxes/drain");
    for (int attempt = 1; ; attempt++) {
      try {
        return sendOnce(method, path, body, raw, rawAnswer, idem);
      } catch (Kosa8Exception e) {
        if (!retry.shouldRetry(e, attempt) || !(safe || e.status() == 429)) throw e;
        sleeper.accept(retry.delay(e, attempt));
      }
    }
  }

  private Object sendOnce(String method, String path, Object body, byte[] raw, boolean rawAnswer, String idemKey) {
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
        .header("Authorization", "Bearer " + key).header("User-Agent", "kosa8-java/" + Kosa8Client.VERSION)
        .header("Accept", rawAnswer ? "application/octet-stream" : "application/json");
    if (idemKey != null) rb.header("Idempotency-Key", idemKey);
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
    Duration ra = parseRetryAfter(retryAfter);
    if (e instanceof Kosa8Exception.RateLimitException rl) rl.withRetryAfter(ra);
    if (ra != null) e.withRetryAfterSeconds(ra.toMillis() / 1000.0);
    else if (body instanceof Map<?, ?> m && m.get("retry_after_s") instanceof Number n && n.doubleValue() > 0) e.withRetryAfterSeconds(n.doubleValue());
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
      CloudOptions.Create o = options == null ? new CloudOptions.Create() : options;
      CloudSandbox box = new CloudSandbox(Kosa8Cloud.this, obj(obj(request("POST", "/v1/sandboxes", o.body(), o.idempotencyKey)).get("sandbox")));
      if (!o.noWait && "creating".equals(box.state())) box.waitRunning();
      return box;
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

    /** count sandboxes with the org's defaults. See {@link #createMany(int, CloudOptions.Batch)}. */
    public SandboxBatch createMany(int count) { return createMany(count, null); }

    /**
     * Up to 1000 sandboxes in one call, paced by kosa8 Cloud (16 at a time), each held to the rules of a create.
     * Past the plan's running limit it makes what fits and lists the rest in {@link SandboxBatch#failed()}
     * (partialOk, the default); {@code partialOk(false)} is all or nothing. Nothing made is an exception. The
     * call carries an Idempotency-Key its retries reuse, so a retry after a timeout or a 5xx makes no second batch.
     */
    public SandboxBatch createMany(int count, CloudOptions.Batch options) {
      CloudOptions.Batch o = options == null ? new CloudOptions.Batch() : options;
      return SandboxBatch.of(Kosa8Cloud.this, obj(request("POST", "/v1/sandboxes/batch", o.body(count), o.idempotencyKey)), count);
    }

    /**
     * Push back the leases of the selected sandboxes to now + extendS (null: each one's own lease_s); returns
     * how many moved. A lapsed lease is not brought back. Send it every lease / 3 or so.
     */
    public int heartbeat(CloudOptions.Selector selector, Integer extendS) {
      if (selector == null) throw new IllegalArgumentException("give a selector: which sandboxes");
      Map<String, Object> b = selector.body();
      if (extendS != null) b.put("extend_s", CloudOptions.checkLease(extendS, "extendS"));
      return obj(request("POST", "/v1/sandboxes/heartbeat", b)).get("extended") instanceof Number n ? n.intValue() : 0;
    }

    /** {@code heartbeat(Selector.run(runId), null)}. */
    public int heartbeat(String runId) { return heartbeat(CloudOptions.Selector.run(runId), null); }

    /**
     * Delete every selected sandbox of the org. kosa8 Cloud deletes up to 1000 a call; this calls again while
     * it says there are more (and stops when a call deleted none), at most 100 calls.
     */
    public DrainResult drain(CloudOptions.Selector selector) {
      if (selector == null) throw new IllegalArgumentException("give a selector: which sandboxes");
      Map<String, Object> b = selector.body();
      int deleted = 0;
      List<String> ids = new ArrayList<>();
      List<FanoutFailure> failed = new ArrayList<>();
      for (int round = 0; round < 100; round++) {
        Map<String, Object> r = obj(request("POST", "/v1/sandboxes/drain", b));
        int n = r.get("deleted") instanceof Number x ? x.intValue() : 0;
        deleted += n;
        if (r.get("ids") instanceof List<?> l) for (Object o : l) ids.add(String.valueOf(o));
        for (Map<String, Object> m : objs(r.get("failed"))) failed.add(FanoutFailure.of(m));
        if (!Boolean.TRUE.equals(r.get("more")) || n == 0) break;
      }
      return new DrainResult(deleted, List.copyOf(ids), List.copyOf(failed));
    }

    /** {@code drain(Selector.run(runId))}. */
    public DrainResult drain(String runId) { return drain(CloudOptions.Selector.run(runId)); }

    /** Grade a solved sandbox by id in a sealed fork. See {@link CloudSandbox#grade(List, CloudOptions.Grade)}. */
    public GradeResult grade(String sandboxId, List<String> cmd, CloudOptions.Grade options) {
      return new CloudSandbox(Kosa8Cloud.this, new LinkedHashMap<>(Map.of("id", sandboxId))).grade(cmd, options);
    }
  }

  /**
   * {@code cloud.orgTemplates()}: the org's own templates, built on a kosa8 Cloud host and kept as a snapshot,
   * so a sandbox created from one ({@code new CloudOptions.Create().template(name)}) is a restore of about a
   * second instead of a cold image pull.
   */
  public final class OrgTemplates {
    private OrgTemplates() {}

    private String p(String ref) { return "/v1/templates/" + Transport.enc(ref); }

    /** Queue a build (202): poll {@link #get} or {@link #waitReady} until its state is ready. */
    public Map<String, Object> build(String name, CloudOptions.TemplateBuild options) {
      if (options == null) throw new IllegalArgumentException("give an image or a dockerfile");
      return obj(obj(request("POST", "/v1/templates", options.body(name))).get("template"));
    }

    /**
     * Queue many image templates at once (each {@code {name, image}}, at most 1000), such as RL task images by
     * digest: {import_id, requested, queued, templates, failed}.
     */
    public Map<String, Object> importImages(List<Map<String, String>> images, CloudOptions.TemplateImport options) {
      CloudOptions.TemplateImport o = options == null ? new CloudOptions.TemplateImport() : options;
      return obj(request("POST", "/v1/templates/import", o.body(images)));
    }

    /** The org's own templates (the org_templates of GET /v1/templates). */
    public List<Map<String, Object>> list() { return objs(obj(request("GET", "/v1/templates", null)).get("org_templates")); }

    /** One template by id (tpl_...) or name, with its build log. */
    public Map<String, Object> get(String idOrName) { return obj(obj(request("GET", p(idOrName), null)).get("template")); }

    /** Delete it: a build in flight stops and its snapshot is deleted; sandboxes made from it are not touched. */
    public void delete(String idOrName) { request("DELETE", p(idOrName), null); }

    /** Poll every interval until it is ready; throws Kosa8Exception if it failed, TimeoutException after timeout. */
    public Map<String, Object> waitReady(String idOrName, Duration timeout, Duration interval) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        Map<String, Object> t = get(idOrName);
        String st = String.valueOf(t.get("state"));
        if ("ready".equals(st)) return t;
        if ("failed".equals(st) || "error".equals(st)) {
          throw new Kosa8Exception("template " + idOrName + " " + st + (t.get("last_error") != null ? ": " + t.get("last_error") : ""));
        }
        if (System.nanoTime() > deadline) {
          throw new Kosa8Exception.TimeoutException("template " + idOrName + " was still " + st + " after " + timeout.toSeconds() + " s", "GET", p(idOrName));
        }
        sleeper.accept(interval);
      }
    }
  }

  /**
   * {@code cloud.tokens()}: short-lived bearer tokens (at most 24 hours) limited to some scopes and, optionally,
   * some sandboxes, for handing a sandbox to an end user without the API key. A token works as the
   * {@code apiKey} of a Kosa8Cloud.
   */
  public final class Tokens {
    private Tokens() {}

    /** A token with these scopes (from {@link CloudOptions#TOKEN_SCOPES}); its "secret" is in the answer only now. */
    public Map<String, Object> create(List<String> scopes, CloudOptions.Token options) {
      CloudOptions.Token o = options == null ? new CloudOptions.Token() : options;
      return obj(obj(request("POST", "/v1/tokens", o.body(scopes))).get("token"));
    }

    /** The org's tokens (never secrets); with all, the last day's revoked and expired ones too. */
    public List<Map<String, Object>> list(boolean all) { return objs(obj(request("GET", "/v1/tokens" + (all ? "?all=1" : ""), null)).get("tokens")); }

    public List<Map<String, Object>> list() { return list(false); }

    /** Revoke it; it stops at once. */
    public Map<String, Object> revoke(String id) {
      if (id == null || id.isEmpty()) throw new IllegalArgumentException("revoke needs a token id");
      return obj(obj(request("DELETE", "/v1/tokens/" + Transport.enc(id), null)).get("token"));
    }
  }

  /** {@code cloud.snapshots()}: the org's durable snapshots. */
  public final class Snapshots {
    private Snapshots() {}

    /** The org's snapshots, newest first. */
    public List<CloudSnapshot> list() { return list(null); }

    /** One sandbox's snapshots (sandboxId null: all), newest first. */
    public List<CloudSnapshot> list(String sandboxId) {
      List<CloudSnapshot> out = new ArrayList<>();
      String q = sandboxId == null ? "" : "?sandbox_id=" + Transport.enc(sandboxId);
      for (Map<String, Object> m : objs(obj(request("GET", "/v1/snapshots" + q, null)).get("snapshots"))) out.add(CloudSnapshot.of(m));
      return out;
    }

    public CloudSnapshot get(String id) { return CloudSnapshot.of(obj(obj(request("GET", "/v1/snapshots/" + Transport.enc(id), null)).get("snapshot"))); }

    public void delete(String id) { request("DELETE", "/v1/snapshots/" + Transport.enc(id), null); }
  }

  /**
   * {@code cloud.secrets()}: the org's secrets. A sandbox created with them holds only placeholders; its egress
   * broker adds the values on the way out.
   */
  public final class Secrets {
    private Secrets() {}

    /** Store (or replace) a secret for upstream (an https origin). See {@link #set(String, Map)}. */
    public Map<String, Object> set(String name, String value, String upstream, boolean pipelines) {
      Map<String, Object> spec = new LinkedHashMap<>();
      spec.put("value", value);
      if (upstream != null) spec.put("upstream", upstream);
      if (pipelines) spec.put("pipelines", true);
      return set(name, spec);
    }

    /**
     * Store (or replace) a secret: value (required; never logged or returned), and optionally upstream, header,
     * scheme, methods, paths, env, pipelines (Pipelines runs may use it). Returns it without its value.
     */
    public Map<String, Object> set(String name, Map<String, ?> spec) {
      CloudOptions.checkSecretName(name);
      if (spec == null || !(spec.get("value") instanceof String v) || v.isEmpty()) throw new IllegalArgumentException("a secret needs a value");
      return obj(obj(request("PUT", "/v1/secrets/" + Transport.enc(name), spec)).get("secret"));
    }

    /** The org's secrets, without values. */
    public List<Map<String, Object>> list() { return objs(obj(request("GET", "/v1/secrets", null)).get("secrets")); }

    /** Delete it; returns the sandboxes still holding it (they keep it until deleted). */
    public List<String> delete(String name) {
      List<String> out = new ArrayList<>();
      Object held = obj(request("DELETE", "/v1/secrets/" + Transport.enc(CloudOptions.checkSecretName(name)), null)).get("still_held_by");
      if (held instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
      return out;
    }
  }

  /**
   * {@code cloud.pipelines()}: kosa8 Pipelines, CI on kosa8 Cloud. A run reads the repository's
   * .kosa8/pipeline.yml (or runs its .github/workflows through act), each job in a sandbox of its own.
   */
  public final class Pipelines {
    private Pipelines() {}

    /** The states a finished run ends in. */
    public static final java.util.Set<String> DONE = java.util.Set.of("success", "failure", "cancelled", "skipped", "error");

    private String p(String id, String suffix) { return "/v1/pipelines/runs/" + Transport.enc(id) + suffix; }

    /** Run a repository at ref (null: its default branch). */
    public Map<String, Object> run(String repoUrl, String ref) {
      Map<String, Object> o = new LinkedHashMap<>();
      if (ref != null) o.put("ref", ref);
      return run(repoUrl, o);
    }

    /**
     * Run a repository; options are the API's: ref, event, workflow, job, inputs, size, secrets (names marked
     * pipelines), token_secret, status_secret, timeout_s. Returns the run.
     */
    public Map<String, Object> run(String repoUrl, Map<String, ?> options) {
      if (repoUrl == null || repoUrl.isEmpty()) throw new IllegalArgumentException("a run needs a repo URL");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("repo_url", repoUrl);
      if (options != null) b.putAll(options);
      return obj(obj(request("POST", "/v1/pipelines/runs", b)).get("run"));
    }

    /** Runs, newest first; state and limit may be null. */
    public List<Map<String, Object>> runs(String state, Integer limit) {
      StringBuilder q = new StringBuilder();
      if (state != null) q.append("&state=").append(Transport.enc(state));
      if (limit != null) q.append("&limit=").append(limit);
      return objs(obj(request("GET", "/v1/pipelines/runs" + (q.length() == 0 ? "" : "?" + q.substring(1)), null)).get("runs"));
    }

    public Map<String, Object> get(String id) { return obj(obj(request("GET", p(id, ""), null)).get("run")); }

    /** Poll a run every interval until it finishes; TimeoutException after timeout. */
    public Map<String, Object> waitFor(String id, Duration timeout, Duration interval) {
      long deadline = System.nanoTime() + timeout.toNanos();
      while (true) {
        Map<String, Object> r = get(id);
        if (DONE.contains(String.valueOf(r.get("state")))) return r;
        if (System.nanoTime() > deadline) {
          throw new Kosa8Exception.TimeoutException("pipeline run " + id + " was still " + r.get("state") + " after " + timeout.toSeconds() + " s", "GET", p(id, ""));
        }
        CloudSandbox.pause(interval);
      }
    }

    /** Its logs by job (job null: every job; after: a previous answer's next), secrets replaced with ***. */
    public Map<String, Object> logs(String id, String job, Integer after) {
      StringBuilder q = new StringBuilder();
      if (job != null) q.append("&job=").append(Transport.enc(job));
      if (after != null) q.append("&after=").append(after);
      return obj(request("GET", p(id, "/logs") + (q.length() == 0 ? "" : "?" + q.substring(1)), null));
    }

    public Map<String, Object> cancel(String id) { return obj(obj(request("POST", p(id, "/cancel"), new LinkedHashMap<>())).get("run")); }

    /** The files its jobs saved: job, idx, path, bytes, url. */
    public List<Map<String, Object>> artifacts(String id) { return objs(obj(request("GET", p(id, "/artifacts"), null)).get("artifacts")); }

    /** One artifact's bytes (a .tar.gz). */
    public byte[] downloadArtifact(String id, String job, int idx) {
      return requestBytes("GET", p(id, "/artifacts/" + Transport.enc(job) + "/" + idx));
    }

    /**
     * A webhook URL for a repository's pushes and pull requests; options are the API's: provider, name, secret,
     * size, secrets, token_secret, status_secret, workflow, timeout_s, cron, cron_ref. The answer's secret is
     * shown only now: give it and url to the git host.
     */
    public Map<String, Object> createHook(String repoUrl, Map<String, ?> options) {
      if (repoUrl == null || repoUrl.isEmpty()) throw new IllegalArgumentException("a hook needs a repo URL");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("repo_url", repoUrl);
      if (options != null) b.putAll(options);
      return obj(obj(request("POST", "/v1/pipelines/hooks", b)).get("hook"));
    }

    /** The org's hooks, without secrets. */
    public List<Map<String, Object>> hooks() { return objs(obj(request("GET", "/v1/pipelines/hooks", null)).get("hooks")); }

    public void deleteHook(String id) { request("DELETE", "/v1/pipelines/hooks/" + Transport.enc(id), null); }
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
