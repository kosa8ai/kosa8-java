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
 * CloudSandbox box = cloud.sandboxes().create("python");
 * System.out.print(box.exec(List.of("python3", "-c", "print(6 * 7)")).stdout()); // 42
 * box.delete();
 * }</pre>
 *
 * <p>Authentication is an API key ({@code k8c_live_...}) made in the cloud dashboard. Errors are the
 * SDK's usual {@link Kosa8Exception}s; each carries the cloud's stable {@link Kosa8Exception#code()}
 * (for example {@code payment_required}, {@code quota_exceeded}).
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

  Object request(String method, String path, Object body) {
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
        .header("Authorization", "Bearer " + key).header("Accept", "application/json");
    if (body != null) {
      rb.header("Content-Type", "application/json");
      rb.method(method, HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8));
    } else {
      rb.method(method, HttpRequest.BodyPublishers.noBody());
    }
    HttpResponse<String> resp;
    try {
      resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new Kosa8Exception.TimeoutException("kosa8 Cloud did not answer within " + timeout.toMillis() / 1000.0 + " s",
          method, path);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Kosa8Exception.ConnectionException("interrupted talking to kosa8 Cloud", method, path, e);
    } catch (IOException e) {
      String why = e.getMessage() != null ? e.getMessage() : (e instanceof ConnectException ? "connection refused" : e.toString());
      throw new Kosa8Exception.ConnectionException("could not reach kosa8 Cloud at " + baseUrl + ": " + why, method, path, e);
    }
    String text = resp.body() == null ? "" : resp.body();
    Object parsed;
    try {
      parsed = text.isEmpty() ? null : Json.parse(text);
    } catch (RuntimeException e) {
      parsed = text;
    }
    if (resp.statusCode() >= 400) throw cloudError(resp.statusCode(), parsed, method, path);
    return parsed;
  }

  /** The cloud answers {error: code, message}: the sentence is the message, and the code is kept. */
  static Kosa8Exception cloudError(int status, Object body, String method, String path) {
    String code = null;
    Object msgBody = body;
    if (body instanceof Map<?, ?> m) {
      if (m.get("error") instanceof String s) code = s;
      Object msg = m.get("message") instanceof String s && !s.isEmpty() ? s : m.get("error");
      Map<String, Object> mb = new LinkedHashMap<>();
      mb.put("error", msg);
      msgBody = mb;
    }
    return Kosa8Exception.fromResponse(status, msgBody, body, method, path).withCode(code);
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

    /** The org's live sandboxes. */
    public List<CloudSandbox> list() { return list(false); }

    /** The org's sandboxes; with all, deleted ones too. */
    public List<CloudSandbox> list(boolean all) {
      List<CloudSandbox> out = new ArrayList<>();
      for (Map<String, Object> s : objs(obj(request("GET", "/v1/sandboxes" + (all ? "?all=1" : ""), null)).get("sandboxes"))) {
        out.add(new CloudSandbox(Kosa8Cloud.this, s));
      }
      return out;
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
