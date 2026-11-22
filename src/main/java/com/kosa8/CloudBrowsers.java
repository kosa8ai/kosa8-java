package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.browsers()}: headless Chromium on demand, driven over CDP. A browser costs
 * {@value #PRICE_PER_HOUR_USD} USD per browser-hour, billed per second, and closes on its own after its ttl.
 *
 * <pre>{@code
 * try (CloudBrowser b = cloud.browsers().create(120, null, null)) {
 *   byte[] png = b.screenshot("https://example.com", false);
 *   // Playwright: chromium().connectOverCDP(b.cdpUrl())
 * }
 * }</pre>
 */
public final class CloudBrowsers {
  /** USD per browser-hour, billed per second (the list answer's price_per_hour_usd is the live figure). */
  public static final double PRICE_PER_HOUR_USD = 0.12;

  private final Kosa8Cloud cloud;

  CloudBrowsers(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** Open a browser with the defaults (ttl 300 s). */
  public CloudBrowser create() { return create(null, null, null); }

  /**
   * Open a browser: ttlS 30 to 3600 (null: 300) before it closes on its own, sandboxId to tie it to a sandbox,
   * name a label (each may be null).
   */
  public CloudBrowser create(Integer ttlS, String sandboxId, String name) {
    if (ttlS != null && (ttlS < 30 || ttlS > 3600)) throw new IllegalArgumentException("ttlS must be from 30 to 3600, not " + ttlS);
    Map<String, Object> b = new LinkedHashMap<>();
    if (ttlS != null) b.put("ttl_s", ttlS);
    if (sandboxId != null) b.put("sandbox_id", sandboxId);
    if (name != null) b.put("name", name);
    return new CloudBrowser(cloud, Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/browsers", b)).get("browser")));
  }

  /** The list answer as sent: browsers (no cdp_url), enabled, limit, price_per_hour_usd. state: null/"open" or "all". */
  public Map<String, Object> info(String state) {
    if (state != null && !state.equals("open") && !state.equals("all")) throw new IllegalArgumentException("state must be open or all");
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/browsers" + (state == null ? "" : "?state=" + state), null));
  }

  /** Open browsers. */
  public List<CloudBrowser> list() { return list(null); }

  /** Browsers (state "all": the last 100, closed ones too). Items have no cdpUrl: refresh() one. */
  public List<CloudBrowser> list(String state) {
    List<CloudBrowser> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(info(state).get("browsers"))) out.add(new CloudBrowser(cloud, m));
    return out;
  }

  /** One browser, with a fresh cdp_url while it is open. */
  public CloudBrowser get(String id) {
    return new CloudBrowser(cloud, Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", "/v1/browsers/" + Transport.enc(id), null)).get("browser")));
  }

  /** Close a browser by id; billing stops. */
  public Map<String, Object> delete(String id) { return Kosa8Cloud.obj(cloud.request("DELETE", "/v1/browsers/" + Transport.enc(id), null)); }

  /**
   * The browser's CDP WebSocket URL (wss://.../v1/browsers/{id}/cdp?token=..., valid 10 minutes) for any CDP
   * client; a fresh one on every call. Null when the browser is closed.
   */
  public String cdpUrl(String id) { return get(id).cdpUrl(); }

  /** The CDP WebSocket's path on the API (open it with the token in a cdp_url, or use {@link #cdpUrl}). */
  public static String cdpPath(String id) { return "/v1/browsers/" + Transport.enc(id) + "/cdp"; }
}
