package com.kosa8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code cloud.audit()}: the org's audit log, a hash chain that shows no event was changed or removed. */
public final class CloudAudit {
  private final Kosa8Cloud cloud;

  CloudAudit(Kosa8Cloud cloud) { this.cloud = cloud; }

  /**
   * A page of events, newest first: {events, next_before_seq}. filters may hold sandbox_id, actor, action, since,
   * until (Unix ms), before_seq (a previous page's next_before_seq) and limit; null for none.
   */
  public Map<String, Object> list(Map<String, ?> filters) {
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/audit" + query(filters), null));
  }

  /** The newest events. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(list(null).get("events")); }

  /** Recompute the org's hash chain: whether it verifies, and where it breaks if not. */
  public Map<String, Object> verify() { return Kosa8Cloud.obj(cloud.request("GET", "/v1/audit/verify", null)); }

  /** Every event (as filtered) oldest first, with its hashes, one JSON object per line (NDJSON). */
  public String exportNdjson(Map<String, ?> filters) {
    return new String(cloud.requestBytes("GET", "/v1/audit/export" + query(filters)), StandardCharsets.UTF_8);
  }

  /** {@link #exportNdjson}, each line parsed. */
  public List<Map<String, Object>> export(Map<String, ?> filters) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (String line : exportNdjson(filters).split("\n")) if (!line.isBlank()) out.add(Kosa8Cloud.obj(Json.parse(line)));
    return out;
  }

  static String query(Map<String, ?> f) {
    if (f == null || f.isEmpty()) return "";
    StringBuilder q = new StringBuilder();
    for (Map.Entry<String, ?> e : f.entrySet()) {
      if (e.getValue() == null) continue;
      q.append(q.length() == 0 ? '?' : '&').append(Transport.enc(e.getKey())).append('=').append(Transport.enc(String.valueOf(e.getValue())));
    }
    return q.toString();
  }
}
