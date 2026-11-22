package com.kosa8;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One cloud browser (headless Chromium), from {@code cloud.browsers().create(...)}. {@link #cdpUrl()} (valid
 * 10 minutes) connects Playwright ({@code chromium().connectOverCDP(url)}) or any CDP client; {@link #refresh()}
 * gets a fresh one. Close it with {@link #close()} (or try-with-resources): billing stops then.
 */
public final class CloudBrowser implements AutoCloseable {
  private final Kosa8Cloud cloud;
  private Map<String, Object> info;

  CloudBrowser(Kosa8Cloud cloud, Map<String, Object> info) {
    this.cloud = cloud;
    this.info = info;
  }

  /** brw_... */
  public String id() { return String.valueOf(info.get("id")); }

  /** open or closed. */
  public String state() { return info.get("state") instanceof String s ? s : ""; }

  /** Its fields as last read: id, name, sandbox_id, state, ttl_s, created_at, expires_at, seconds, ... */
  public Map<String, Object> info() { return info; }

  /**
   * The {@code wss://.../v1/browsers/{id}/cdp?token=...} URL a CDP client opens as it is, while the browser
   * is open (null when closed, or for a browser from a list: {@link #refresh()} it).
   */
  public String cdpUrl() { return info.get("cdp_url") instanceof String s && !s.isEmpty() ? s : null; }

  private String path() { return "/v1/browsers/" + Transport.enc(id()); }

  /** Read it again: its state and, while open, a fresh {@link #cdpUrl()}. */
  public CloudBrowser refresh() {
    info = Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", path(), null)).get("browser"));
    return this;
  }

  /**
   * Load url in it: {url, title} and, if asked, text and screenshot_png_base64 (fullPage: the whole page, not
   * the viewport). A null flag takes the server's default.
   */
  public Map<String, Object> page(String url, Boolean screenshot, Boolean text, Boolean fullPage) {
    if (url == null || url.isEmpty()) throw new IllegalArgumentException("url is required");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("url", url);
    if (screenshot != null) b.put("screenshot", screenshot);
    if (text != null) b.put("text", text);
    if (fullPage != null) b.put("full_page", fullPage);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/browsers/" + Transport.enc(id()) + "/page", b)).get("page"));
  }

  /** Load url: url, title and the page's text. */
  public Map<String, Object> goTo(String url) { return page(url, null, true, null); }

  /** Load url and return a PNG of the viewport (fullPage: the whole page). */
  public byte[] screenshot(String url, boolean fullPage) {
    Map<String, Object> p = page(url, true, null, fullPage ? Boolean.TRUE : null);
    if (!(p.get("screenshot_png_base64") instanceof String s) || s.isEmpty()) {
      throw new Kosa8Exception("the API sent no screenshot" + (p.get("screenshot_note") != null ? ": " + p.get("screenshot_note") : ""));
    }
    return Base64.getDecoder().decode(s);
  }

  /** Close it; billing stops. Answers {ok, id}. */
  public Map<String, Object> delete() {
    Map<String, Object> r = Kosa8Cloud.obj(cloud.request("DELETE", path(), null));
    Map<String, Object> next = new LinkedHashMap<>(info);
    next.put("state", "closed");
    next.remove("cdp_url");
    info = next;
    return r;
  }

  /** {@link #delete()}, for try-with-resources; a failure to close is not thrown over the real error. */
  @Override
  public void close() {
    try {
      delete();
    } catch (Kosa8Exception ignored) {
      // closing on the way out must not hide the real error
    }
  }

  /** Never the cdp_url (it carries a token). */
  @Override
  public String toString() { return "CloudBrowser(id=" + id() + ", state=" + state() + ")"; }
}
