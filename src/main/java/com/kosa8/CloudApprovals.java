package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.approvals()}: bot actions waiting for a person's decision. An approval is {id, run_id, bot_id,
 * bot_name, step_key, action, tier, args, rationale, requested_role, state, expires_at, decision, created_at}.
 *
 * <p>An agent never approves its own action: a person approves in the web app, the desktop app, the CLI or Slack.
 * {@link #approve} and {@link #reject} work only with a person's key (one bound to a member, as {@code kosa8 login}
 * makes); a capability token, MCP or an org key bound to no member gets
 * {@link Kosa8Exception.HumanApprovalRequiredException}. Expired or already decided: a 409
 * {@link Kosa8Exception.ConflictException} (code expired / decided).
 */
public final class CloudApprovals {
  private static final List<String> STATES = List.of("pending", "decided", "all");

  private final Kosa8Cloud cloud;

  CloudApprovals(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** Approvals in state (pending, decided or all; null: pending), limit null for the server's default. */
  public List<Map<String, Object>> list(String state, Integer limit) {
    String s = state == null ? "pending" : state;
    if (!STATES.contains(s)) throw new IllegalArgumentException("state is one of " + STATES + ", not " + state);
    if (limit != null && limit < 1) throw new IllegalArgumentException("limit must be positive");
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("state", s);
    q.put("limit", limit);
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/approvals" + CloudAudit.query(q), null)).get("approvals"));
  }

  /** The pending approvals. */
  public List<Map<String, Object>> list() { return list(null, null); }

  /** One approval. */
  public Map<String, Object> get(String id) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", "/v1/approvals/" + CloudBots.id("an approval", id), null)).get("approval"));
  }

  private Map<String, Object> decide(String id, String decision, String reason, Map<String, Object> editedArgs) {
    String path = "/v1/approvals/" + CloudBots.id("a decision", id) + "/decide";
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("decision", decision);
    if (reason != null) b.put("reason", reason);
    if (editedArgs != null) b.put("edited_args", editedArgs);
    return Kosa8Cloud.obj(cloud.request("POST", path, b));
  }

  /** Approve as the person whose key this is: {approval, run}. */
  public Map<String, Object> approve(String id) { return approve(id, null, null); }

  /** Approve, with a reason and/or edited args (checked against the action's schema by the server): {approval, run}. */
  public Map<String, Object> approve(String id, String reason, Map<String, Object> editedArgs) { return decide(id, "approve", reason, editedArgs); }

  /** Reject (reason may be null): {approval, run}. */
  public Map<String, Object> reject(String id, String reason) { return decide(id, "reject", reason, null); }
}
