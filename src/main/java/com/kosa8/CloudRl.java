package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code cloud.rl()}: comparing RL runs and alert rules on them. A run itself is {@link Run}. */
public final class CloudRl {
  private final Kosa8Cloud cloud;

  CloudRl(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** Two to four runs side by side. */
  public Map<String, Object> compare(List<String> runIds) {
    if (runIds == null || runIds.size() < 2 || runIds.size() > 4) throw new IllegalArgumentException("compare takes 2 to 4 run ids");
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/rl/compare?ids=" + Transport.enc(String.join(",", runIds)), null));
  }

  /** {alerts, event, kinds}: the alert rules (runId null: every rule) and the kinds one may make. */
  public Map<String, Object> alertsInfo(String runId) {
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/rl/alerts" + (runId == null ? "" : "?run_id=" + Transport.enc(runId)), null));
  }

  /** The alert rules (runId null: every rule). */
  public List<Map<String, Object>> alerts(String runId) { return Kosa8Cloud.objs(alertsInfo(runId).get("alerts")); }

  /**
   * Add an alert rule: kind (one of alertsInfo's kinds), threshold, and optionally runId (null: every run) and
   * window. Answers the rule.
   */
  public Map<String, Object> createAlert(String runId, String kind, double threshold, Integer window) {
    if (kind == null || kind.isEmpty()) throw new IllegalArgumentException("an alert needs a kind");
    Map<String, Object> b = new LinkedHashMap<>();
    if (runId != null) b.put("run_id", runId);
    b.put("kind", kind);
    b.put("threshold", threshold);
    if (window != null) b.put("window", window);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/rl/alerts", b)).get("alert"));
  }

  /** Change, pause (enabled false) or resume a rule; null leaves a field as it is. */
  public Map<String, Object> updateAlert(String id, Double threshold, Integer window, Boolean enabled) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (threshold != null) b.put("threshold", threshold);
    if (window != null) b.put("window", window);
    if (enabled != null) b.put("enabled", enabled);
    if (b.isEmpty()) throw new IllegalArgumentException("give threshold, window or enabled");
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("PATCH", "/v1/rl/alerts/" + Transport.enc(id), b)).get("alert"));
  }

  /** Delete a rule. */
  public void deleteAlert(String id) { cloud.request("DELETE", "/v1/rl/alerts/" + Transport.enc(id), null); }
}
