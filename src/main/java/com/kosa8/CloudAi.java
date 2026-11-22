package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@code cloud.ai()}: the org's AI gateway. Provider keys are kept server-side (never sent back, never put in a
 * sandbox); calls are cached, rate limited and costed per org and per sandbox. A sandbox created with
 * {@code new CloudOptions.Create().aiGateway(true)} gets OPENAI_BASE_URL, ANTHROPIC_BASE_URL, KOSA8_AI_GATEWAY_URL
 * and KOSA8_AI_TOKEN in its env. Outside a sandbox, point a provider SDK at {@link #gatewayUrl(String)} with the
 * kosa8 API key as its key.
 */
public final class CloudAi {
  private static final Pattern MONTH = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");
  private static final Pattern PROVIDER = Pattern.compile("[a-z0-9-]{1,40}");

  private final Kosa8Cloud cloud;

  CloudAi(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** {settings: {enabled, all_sandboxes, cache_ttl_s, rate_per_min}, available, base_url, gateway_id, providers, ...}. */
  public Map<String, Object> settings() { return Kosa8Cloud.obj(cloud.request("GET", "/v1/ai/settings", null)); }

  /** Change the given settings only (null: unchanged); at least one is required. */
  public Map<String, Object> updateSettings(Boolean enabled, Boolean allSandboxes, Integer cacheTtlS, Integer ratePerMin) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (enabled != null) b.put("enabled", enabled);
    if (allSandboxes != null) b.put("all_sandboxes", allSandboxes);
    if (cacheTtlS != null) b.put("cache_ttl_s", cacheTtlS);
    if (ratePerMin != null) b.put("rate_per_min", ratePerMin);
    if (b.isEmpty()) throw new IllegalArgumentException("give at least one setting to change");
    return Kosa8Cloud.obj(cloud.request("PATCH", "/v1/ai/settings", b));
  }

  /** Store the org's own key for a provider (openai, anthropic, groq, ...). It is sealed and never sent back. */
  public Map<String, Object> setKey(String provider, String key) {
    if (key == null || key.isEmpty()) throw new IllegalArgumentException("provider and key are required");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("key", key);
    return Kosa8Cloud.obj(cloud.request("PUT", "/v1/ai/keys/" + Transport.enc(check(provider)), b));
  }

  /** The stored keys: provider, hint (the last characters, never the key), created_at, updated_at. */
  public List<Map<String, Object>> keys() { return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/ai/keys", null)).get("keys")); }

  /** Remove a provider's key. */
  public Map<String, Object> deleteKey(String provider) {
    return Kosa8Cloud.obj(cloud.request("DELETE", "/v1/ai/keys/" + Transport.enc(check(provider)), null));
  }

  /** This month's model calls and cost: month, total, by_model, by_sandbox, recent. */
  public Map<String, Object> usage() { return usage(null); }

  /** A month's (YYYY-MM) model calls and cost. */
  public Map<String, Object> usage(String month) {
    if (month != null && !MONTH.matcher(month).matches()) throw new IllegalArgumentException("month must be YYYY-MM, not " + month);
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/ai/usage" + (month == null ? "" : "?month=" + month), null));
  }

  /**
   * The base URL a provider's own SDK points at to call through the gateway, ending in a slash, e.g.
   * {@code https://api.kosa8.com/v1/ai/gw/openai/} (OpenAI's base_url) or {@code .../v1/ai/gw/anthropic/}. Use the
   * kosa8 API key (or a sandbox's KOSA8_AI_TOKEN) as the SDK's API key; the org's provider key is added by kosa8.
   */
  public String gatewayUrl(String provider) { return cloud.baseUrl() + "/v1/ai/gw/" + Transport.enc(check(provider)) + "/"; }

  /**
   * One model call through the gateway in the provider's own request shape, e.g.
   * {@code call("openai", "chat/completions", Map.of("model", "gpt-4.1-mini", "messages", ...))}. Answers the
   * provider's JSON. Not retried (a model call is not idempotent) beyond a 429.
   */
  public Map<String, Object> call(String provider, String path, Object body) {
    if (path == null || path.isEmpty()) throw new IllegalArgumentException("path is required, e.g. chat/completions");
    String p = path.startsWith("/") ? path.substring(1) : path;
    return Kosa8Cloud.obj(cloud.request("POST", String.format("/v1/ai/gw/%s/%s", Transport.enc(check(provider)), p), body));
  }

  private static String check(String provider) {
    if (provider == null || !PROVIDER.matcher(provider).matches()) {
      throw new IllegalArgumentException("provider must be a name such as openai or anthropic, not " + provider);
    }
    return provider;
  }
}
