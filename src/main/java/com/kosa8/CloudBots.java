package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.bots()}: Autopilot bots, small sponsored automations on the org's sandboxes (sleep what is idle, guard
 * spend, block a threat, open a draft PR). A bot is saved as a draft from a template, a spec or a sentence
 * ({@link #compile}), tried with a dry run ({@link #run(String)}), and only a person turns it on ({@link #enable}).
 * Actions above T2 wait for a person's decision ({@link Kosa8Cloud#approvals()}).
 *
 * <p>An agent never approves its own action: a person approves in the web app, the desktop app, the CLI or Slack.
 * {@link #enable} and {@link #resumeAll} throw {@link Kosa8Exception.HumanApprovalRequiredException} (403
 * {@code human_required}) for a capability token, MCP or an org key bound to no member.
 */
public final class CloudBots {
  /** The longest sentence {@link #compile} takes, in characters. */
  public static final int MAX_COMPILE_CHARS = 500;

  private final Kosa8Cloud cloud;

  CloudBots(Kosa8Cloud cloud) { this.cloud = cloud; }

  static String id(String what, String id) {
    if (id == null || id.isEmpty()) throw new IllegalArgumentException(what + " needs an id");
    return Transport.enc(id);
  }

  private static String p(String id) { return "/v1/bots/" + id("a bot", id); }

  private static String r(String runId) { return "/v1/bot-runs/" + id("a run", runId); }

  private Map<String, Object> call(String method, String path, Object body, String key) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request(method, path, body)).get(key));
  }

  /** {bots: [bot], paused_all, paused_all_at, pending_approvals}. */
  public Map<String, Object> list() { return Kosa8Cloud.obj(cloud.request("GET", "/v1/bots", null)); }

  /** The ready-made bots, ranked for this org: [{id, version, title, summary, tier, free, spec, plan, never, ...}]. */
  public List<Map<String, Object>> templates() {
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/bot-templates", null)).get("templates"));
  }

  /** One ready-made bot. */
  public Map<String, Object> template(String id) { return call("GET", "/v1/bot-templates/" + id("a template", id), null, "template"); }

  /** What a template or spec would do, saving nothing: {plan, spec, replay?}. Exactly one of template and spec. */
  public Map<String, Object> plan(CloudOptions.Bot o) {
    if (o == null) throw new IllegalArgumentException("plan needs a template or a spec");
    o.checkOneOf("plan");
    return Kosa8Cloud.obj(cloud.request("POST", "/v1/bots/plan", o.body(true)));
  }

  /** A sentence (at most 500 characters) made into {spec, plan, source, notes}. Never saves or enables anything. */
  public Map<String, Object> compile(String text) {
    if (text == null || text.isEmpty()) throw new IllegalArgumentException("compile needs a sentence");
    int n = text.codePointCount(0, text.length());
    if (n > MAX_COMPILE_CHARS) throw new IllegalArgumentException("compile takes at most " + MAX_COMPILE_CHARS + " characters, not " + n);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("text", text);
    return Kosa8Cloud.obj(cloud.request("POST", "/v1/bots/compile", b));
  }

  /** Save a draft bot from a template or a spec (exactly one). It is not on until a person enables it. */
  public Map<String, Object> create(CloudOptions.Bot o) {
    if (o == null) throw new IllegalArgumentException("create needs a template or a spec");
    o.checkOneOf("create");
    return call("POST", "/v1/bots", o.body(false), "bot");
  }

  /** One bot, with its spec, plan and versions. */
  public Map<String, Object> get(String id) { return call("GET", p(id), null, "bot"); }

  /** Change a bot's name, spec, sponsor, budget, scope or notify. A new spec is a new version. */
  public Map<String, Object> update(String id, CloudOptions.Bot o) {
    if (o == null) throw new IllegalArgumentException("update needs at least one field to change");
    if (o.template != null) throw new IllegalArgumentException("update takes a spec, not a template");
    Map<String, Object> b = o.body(false);
    if (b.isEmpty()) throw new IllegalArgumentException("update needs at least one field to change");
    return call("PATCH", p(id), b, "bot");
  }

  /** Delete a bot; its runs are kept and its pending approvals rejected. Answers {deleted: true}. */
  public Map<String, Object> delete(String id) { return Kosa8Cloud.obj(cloud.request("DELETE", p(id), null)); }

  /** Turn a bot on. Only a person can: any other key gets {@link Kosa8Exception.HumanApprovalRequiredException}. */
  public Map<String, Object> enable(String id) { return call("POST", p(id) + "/enable", new LinkedHashMap<>(), "bot"); }

  /** Pause a bot. */
  public Map<String, Object> disable(String id) { return call("POST", p(id) + "/disable", new LinkedHashMap<>(), "bot"); }

  /** A dry run now: what it would do plus a replay of the last 7 days. Changes nothing, never billed. */
  public Map<String, Object> run(String id) { return run(id, true); }

  /** Run a bot now; dryRun false runs it for real (the bot must be on). */
  public Map<String, Object> run(String id, boolean dryRun) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("dry_run", dryRun);
    return call("POST", p(id) + "/runs", b, "run");
  }

  /** A bot's recent runs (limit 1..100, null: the server's default). */
  public List<Map<String, Object>> runs(String id, Integer limit) {
    if (limit != null && (limit < 1 || limit > 100)) throw new IllegalArgumentException("limit is 1..100, not " + limit);
    String path = p(id) + "/runs" + (limit == null ? "" : "?limit=" + limit);
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", path, null)).get("runs"));
  }

  /** A bot's recent runs. */
  public List<Map<String, Object>> runs(String id) { return runs(id, null); }

  /** One run: {id, bot_id, dry_run, state, trigger, steps, rationale, cost_usd, replay?, ...}. */
  public Map<String, Object> getRun(String runId) { return call("GET", r(runId), null, "run"); }

  /** Cancel a run; its pending approvals are rejected. */
  public Map<String, Object> cancelRun(String runId) { return call("POST", r(runId) + "/cancel", new LinkedHashMap<>(), "run"); }

  /** Undo one step of a run that has an undo (wake a sandbox, lift the bot's own block). */
  public Map<String, Object> undoStep(String runId, String key) {
    String path = r(runId) + "/steps/" + id("undoStep", key) + "/undo";
    return call("POST", path, new LinkedHashMap<>(), "run");
  }

  /** Stop every bot of the org (admin; an agent may): {paused_all, at}. */
  public Map<String, Object> pauseAll() { return Kosa8Cloud.obj(cloud.request("POST", "/v1/bots/pause-all", new LinkedHashMap<>())); }

  /**
   * Let the bots run again (admin, and only a person: any other key gets
   * {@link Kosa8Exception.HumanApprovalRequiredException}): {paused_all, at}.
   */
  public Map<String, Object> resumeAll() { return Kosa8Cloud.obj(cloud.request("POST", "/v1/bots/resume-all", new LinkedHashMap<>())); }
}
