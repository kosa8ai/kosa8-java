package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.recordings()}: desktop session recordings (MP4), made when a sandbox created with
 * {@code new CloudOptions.Create().record(true)} is deleted, kept for the plan's retention.
 */
public final class CloudRecordings {
  private final Kosa8Cloud cloud;

  CloudRecordings(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** Newest first: {recordings, configured, retention_days}. sandboxId and limit (1 to 200) may be null. */
  public Map<String, Object> list(String sandboxId, Integer limit) {
    if (limit != null && (limit < 1 || limit > 200)) throw new IllegalArgumentException("limit must be from 1 to 200");
    StringBuilder q = new StringBuilder();
    if (sandboxId != null) q.append("&sandbox_id=").append(Transport.enc(sandboxId));
    if (limit != null) q.append("&limit=").append(limit);
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/recordings" + (q.length() == 0 ? "" : "?" + q.substring(1)), null));
  }

  /** The org's recordings, newest first. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(list(null, null).get("recordings")); }

  /** A signed download URL for its MP4: {url, expires_at, bytes}. ttlS 60 to 86400 (null: 900). */
  public Map<String, Object> link(String id, Integer ttlS) {
    if (ttlS != null && (ttlS < 60 || ttlS > 86_400)) throw new IllegalArgumentException("ttlS must be from 60 to 86400");
    Map<String, Object> b = new LinkedHashMap<>();
    if (ttlS != null) b.put("ttl_s", ttlS);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/recordings/" + Transport.enc(id) + "/link", b)).get("link"));
  }

  /** Delete it now. */
  public void delete(String id) { cloud.request("DELETE", "/v1/recordings/" + Transport.enc(id), null); }
}
