package com.kosa8;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** cloud.bots(), cloud.approvals() and HumanApprovalRequiredException, against a stub control plane. */
public final class CloudBotsTest extends ClientTest {
  static CloudTest.Answer ok(Object body) { return new CloudTest.Answer(200, body); }

  static Map<String, Object> m(Object... kv) { return CloudFeaturesTest.m(kv); }

  static List<String> lines(CloudTest.Stub stub) {
    List<String> out = new ArrayList<>();
    for (CloudTest.Seen s : stub.seen) out.add(s.method() + " " + s.path());
    return out;
  }

  public void testBotsUseTheContractPathsAndBodies() throws Exception {
    Map<String, Object> bot = m("id", "bot_1", "name", "Spend guard", "state", "draft", "template", "spend-guard@1");
    Map<String, Object> run = m("id", "brun_1", "bot_id", "bot_1", "dry_run", true, "state", "succeeded",
        "steps", List.of(m("key", "s1", "label", "Sleep idle box", "status", "done")));
    Map<String, Object> tpl = m("id", "spend-guard", "version", 1, "title", "Spend guard", "spec", m("triggers", List.of()));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      String u = s.path(), me = s.method();
      if (me.equals("GET") && u.equals("/v1/bots")) return ok(m("bots", List.of(bot), "paused_all", false, "paused_all_at", null, "pending_approvals", 2));
      if (u.equals("/v1/bot-templates")) return ok(m("templates", List.of(tpl)));
      if (u.startsWith("/v1/bot-templates/")) return ok(m("template", tpl));
      if (u.equals("/v1/bots/plan")) return ok(m("plan", m("summary", "s"), "spec", m(), "replay", m("events", 3)));
      if (u.equals("/v1/bots/compile")) return ok(m("spec", m(), "plan", m(), "source", "rules", "notes", List.of()));
      if (u.endsWith("-all")) return ok(m("paused_all", u.contains("pause-all"), "at", 5));
      if (me.equals("DELETE")) return ok(m("deleted", true));
      if (u.contains("/runs?") || (me.equals("GET") && u.endsWith("/runs"))) return ok(m("runs", List.of(run)));
      if (u.contains("/runs") || u.startsWith("/v1/bot-runs/")) return new CloudTest.Answer(201, m("run", run));
      return ok(m("bot", bot));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudBots b = cloud.bots();
      eq(2L, b.list().get("pending_approvals"), "list");
      eq("spend-guard", b.templates().get(0).get("id"), "templates");
      eq("Spend guard", b.template("spend-guard").get("title"), "template");
      eq(3L, ((Map<?, ?>) b.plan(new CloudOptions.Bot().template("spend-guard").name("g").replay(true)).get("replay")).get("events"), "plan");
      b.plan(new CloudOptions.Bot().spec(m("triggers", List.of())));
      eq("rules", b.compile("sleep idle dev boxes after 20 minutes").get("source"), "compile");
      eq("draft", b.create(new CloudOptions.Bot().template("spend-guard").name("g").sponsor("a@b.co").budgetUsd(5)
          .scope(m("labels", m("env", "dev"))).notify(m("mode", "digest"))).get("state"), "create");
      b.create(new CloudOptions.Bot().spec(m("x", 1)));
      eq("bot_1", b.get("bot_1").get("id"), "get");
      b.update("bot_1", new CloudOptions.Bot().name("n2").spec(m("y", 2)));
      eq(true, b.delete("bot_1").get("deleted"), "delete");
      b.enable("bot_1");
      b.disable("bot_1");
      eq(true, b.run("bot_1").get("dry_run"), "run");
      b.run("bot_1", false);
      eq(1, b.runs("bot_1", 10).size(), "runs");
      b.runs("bot_1");
      eq("brun_1", b.getRun("brun_1").get("id"), "getRun");
      b.cancelRun("brun_1");
      b.undoStep("brun_1", "s1");
      eq(true, b.pauseAll().get("paused_all"), "pauseAll");
      eq(false, b.resumeAll().get("paused_all"), "resumeAll");
      b.get("bot_a/b c");

      eq(List.of("GET /v1/bots", "GET /v1/bot-templates", "GET /v1/bot-templates/spend-guard", "POST /v1/bots/plan", "POST /v1/bots/plan",
          "POST /v1/bots/compile", "POST /v1/bots", "POST /v1/bots", "GET /v1/bots/bot_1", "PATCH /v1/bots/bot_1", "DELETE /v1/bots/bot_1",
          "POST /v1/bots/bot_1/enable", "POST /v1/bots/bot_1/disable", "POST /v1/bots/bot_1/runs", "POST /v1/bots/bot_1/runs",
          "GET /v1/bots/bot_1/runs?limit=10", "GET /v1/bots/bot_1/runs", "GET /v1/bot-runs/brun_1", "POST /v1/bot-runs/brun_1/cancel",
          "POST /v1/bot-runs/brun_1/steps/s1/undo", "POST /v1/bots/pause-all", "POST /v1/bots/resume-all", "GET /v1/bots/bot_a%2Fb%20c"),
          lines(stub), "paths");
      List<Object> bodies = new ArrayList<>();
      for (CloudTest.Seen s : stub.seen) bodies.add(s.body());
      eq(Arrays.asList(null, null, null,
          m("template", "spend-guard", "name", "g", "replay", true),
          m("spec", m("triggers", List.of())),
          m("text", "sleep idle dev boxes after 20 minutes"),
          m("template", "spend-guard", "name", "g", "sponsor", "a@b.co", "budget_usd", 5L, "scope", m("labels", m("env", "dev")), "notify", m("mode", "digest")),
          m("spec", m("x", 1L)),
          null,
          m("spec", m("y", 2L), "name", "n2"),
          null, m(), m(),
          m("dry_run", true), m("dry_run", false),
          null, null, null, m(), m(), m(), m(), null), bodies, "bodies");
    }
  }

  public void testBotChecksSendNothing() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> ok(m()))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudBots b = cloud.bots();
      raises(IllegalArgumentException.class, () -> b.create(new CloudOptions.Bot().name("x")));
      raises(IllegalArgumentException.class, () -> b.create(new CloudOptions.Bot().template("t").spec(m())));
      raises(IllegalArgumentException.class, () -> b.create(null));
      raises(IllegalArgumentException.class, () -> b.plan(new CloudOptions.Bot()));
      raises(IllegalArgumentException.class, () -> b.compile("a".repeat(501)));
      raises(IllegalArgumentException.class, () -> b.compile(""));
      raises(IllegalArgumentException.class, () -> b.get(""));
      raises(IllegalArgumentException.class, () -> b.update("bot_1", new CloudOptions.Bot()));
      raises(IllegalArgumentException.class, () -> b.update("bot_1", new CloudOptions.Bot().template("t")));
      raises(IllegalArgumentException.class, () -> b.runs("bot_1", 101));
      raises(IllegalArgumentException.class, () -> b.undoStep("brun_1", ""));
      raises(IllegalArgumentException.class, () -> cloud.approvals().list("maybe", null));
      raises(IllegalArgumentException.class, () -> cloud.approvals().approve(null));
      eq(0, stub.seen.size(), "nothing sent");
      b.compile("é".repeat(500)); // 500 characters, more bytes: allowed
      eq(1, stub.seen.size(), "500 characters sent");
    }
  }

  public void testApprovalsUseTheContractPathsAndBodies() throws Exception {
    Map<String, Object> appr = m("id", "appr_1", "run_id", "brun_1", "action", "sandbox.delete", "tier", "T3", "state", "pending");
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().startsWith("/v1/approvals?")) return ok(m("approvals", List.of(appr)));
      if (s.path().endsWith("/decide")) return ok(m("approval", m("id", "appr_1", "state", "approved"), "run", m("id", "brun_1", "state", "running")));
      return ok(m("approval", appr));
    })) {
      CloudApprovals a = stub.cloud().approvals();
      eq("appr_1", a.list().get(0).get("id"), "list");
      a.list("all", 5);
      eq("T3", a.get("appr_1").get("tier"), "get");
      eq("running", ((Map<?, ?>) a.approve("appr_1", "ok", m("id", "sbx_2")).get("run")).get("state"), "approve");
      a.approve("appr_1");
      a.reject("appr_1", "no");
      eq(List.of("GET /v1/approvals?state=pending", "GET /v1/approvals?state=all&limit=5", "GET /v1/approvals/appr_1",
          "POST /v1/approvals/appr_1/decide", "POST /v1/approvals/appr_1/decide", "POST /v1/approvals/appr_1/decide"), lines(stub), "paths");
      eq(m("decision", "approve", "reason", "ok", "edited_args", m("id", "sbx_2")), stub.seen.get(3).body(), "approve body");
      eq(m("decision", "approve"), stub.seen.get(4).body(), "plain approve body");
      eq(m("decision", "reject", "reason", "no"), stub.seen.get(5).body(), "reject body");
    }
  }

  public void testHumanRequiredIsTyped() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> new CloudTest.Answer(403,
        m("error", "human_required", "message", "a person must approve this: open app.kosa8.com/app#autopilot or Slack")))) {
      Kosa8Cloud cloud = stub.cloud();
      List<Runnable> calls = List.of(() -> cloud.approvals().approve("appr_1"), () -> cloud.approvals().reject("appr_1", null),
          () -> cloud.bots().enable("bot_1"), () -> cloud.bots().resumeAll());
      for (Runnable r : calls) {
        Kosa8Exception.HumanApprovalRequiredException e = raises(Kosa8Exception.HumanApprovalRequiredException.class, r::run);
        check(e instanceof Kosa8Exception.PolicyException && e instanceof Kosa8Exception.ApiException, "subclass of the API error");
        eq(403, e.status(), "status");
        eq("human_required", e.code(), "code");
        check(e.getMessage().contains("a person must approve"), "message: " + e.getMessage());
      }
    }
    Kosa8Exception plain = Kosa8Cloud.cloudError(403, m("error", "forbidden", "message", "no"), "POST", "/v1/bots/bot_1/enable");
    check(plain.getClass() == Kosa8Exception.PolicyException.class, "plain 403 unchanged: " + plain.getClass());
  }
}
