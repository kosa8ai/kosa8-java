package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.webhooks()}: endpoints kosa8 Cloud posts the org's events to, signed with a whsec_ secret
 * (shown once, on create). Failed deliveries are retried.
 */
public final class CloudWebhooks {
  private final Kosa8Cloud cloud;

  CloudWebhooks(Kosa8Cloud cloud) { this.cloud = cloud; }

  private static String p(String id) { return "/v1/webhooks/" + Transport.enc(id); }

  /** {webhooks, events}: the endpoints (no secrets) and every event name one may subscribe to. */
  public Map<String, Object> info() { return Kosa8Cloud.obj(cloud.request("GET", "/v1/webhooks", null)); }

  /** The endpoints, without secrets. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(info().get("webhooks")); }

  /**
   * Add an https endpoint for events (null: every event) with an optional description. The answer's
   * webhook.secret (whsec_...) is shown only now: verify deliveries with it.
   */
  public Map<String, Object> create(String url, List<String> events, String description) {
    if (url == null || url.isEmpty()) throw new IllegalArgumentException("a webhook needs a url");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("url", url);
    if (events != null) b.put("events", List.copyOf(events));
    if (description != null) b.put("description", description);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/webhooks", b)).get("webhook"));
  }

  /** Stop sending to it, retries included. */
  public Map<String, Object> delete(String id) { return Kosa8Cloud.obj(cloud.request("DELETE", p(id), null)); }

  /** Its recent deliveries (limit null: the server's default). */
  public List<Map<String, Object>> deliveries(String id, Integer limit) {
    String path = "/v1/webhooks/" + Transport.enc(id) + "/deliveries" + (limit == null ? "" : "?limit=" + limit);
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", path, null)).get("deliveries"));
  }

  /** Send a webhook.test event now; answers the delivery. */
  public Map<String, Object> test(String id) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/webhooks/" + Transport.enc(id) + "/test", new LinkedHashMap<>())).get("delivery"));
  }
}
