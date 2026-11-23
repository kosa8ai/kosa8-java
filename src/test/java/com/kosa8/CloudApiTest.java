package com.kosa8;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The 0.6.0 Cloud API groups against a stub control plane: retries on database_busy / template_building, small
 * sizes, browsers, the AI gateway, jobs, benchmarks, recordings, embeds, preview domains, org egress, burst,
 * limits, audit, API keys, webhooks, RL compare and alerts, and the literal item paths.
 */
public final class CloudApiTest extends ClientTest {
  static CloudTest.Answer ok(Object body) { return new CloudTest.Answer(200, body); }

  static Map<String, Object> m(Object... kv) { return CloudFeaturesTest.m(kv); }

  static CloudTest.Seen last(CloudTest.Stub stub) { return CloudFeaturesTest.last(stub); }

  static final Map<String, Object> BUSY = m("error", "database_busy", "message", "kosa8 Cloud's database is overloaded right now",
      "failure_class", "infra", "retryable", true, "retry_after", 2, "retry_after_s", 2);

  public void testDatabaseBusyIsRetriedEvenOnAPlainPost() throws Exception {
    List<CloudTest.Answer> answers = new ArrayList<>(List.of(
        new CloudTest.Answer(503, BUSY, Map.of("Retry-After", "2")),
        new CloudTest.Answer(201, m("webhook", m("id", "wh_1", "secret", "whsec_x")))));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      Map<String, Object> w = stub.cloud(sleeps).webhooks().create("https://example.com/hook", List.of("sandbox.deleted"), null);
      eq("wh_1", w.get("id"), "created after the retry");
      eq(2, stub.seen.size(), "one retry");
      check(sleeps.get(0).toMillis() >= 2000, "waited Retry-After: " + sleeps);
    }
  }

  public void testRetryAfterInTheBodyWithoutAHeader() throws Exception {
    Map<String, Object> busy = new java.util.LinkedHashMap<>(BUSY);
    busy.remove("retry_after_s");
    List<CloudTest.Answer> answers = new ArrayList<>(List.of(new CloudTest.Answer(503, busy), ok(m("ok", true))));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      stub.cloud(sleeps).functions().invoke("f");
      check(sleeps.get(0).toMillis() >= 2000, "retry_after honoured: " + sleeps);
    }
    Kosa8Exception e = Kosa8Cloud.cloudError(503, busy, "POST", "/v1/x");
    eq(2.0, e.retryAfterSeconds(), "retry_after read");
    check(e.retryable(), "database_busy is retryable");
  }

  public void testTemplateBuildingIsWaitedOut() throws Exception {
    Map<String, Object> building = m("error", "template_building", "message", "being built", "eta_s", 40, "retry_after_s", 10,
        "failure_class", "infra", "retryable", true);
    List<CloudTest.Answer> answers = new ArrayList<>(List.of(
        new CloudTest.Answer(409, building, Map.of("Retry-After", "10")),
        new CloudTest.Answer(201, m("sandbox", m("id", "sbx_1", "state", "running")))));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      CloudSandbox b = stub.cloud(sleeps).sandboxes().create(new CloudOptions.Create().template("swe-bench-verified/django__django-11099"));
      eq("sbx_1", b.id(), "created");
      check(sleeps.get(0).toMillis() >= 10_000, "waited: " + sleeps);
      eq(stub.seen.get(0).headers().get("idempotency-key"), stub.seen.get(1).headers().get("idempotency-key"), "same key");
    }
  }

  public void testOtherFailuresOfAPlainPostAreNotRetried() throws Exception {
    List<CloudTest.Answer> answers = new ArrayList<>(List.of(
        new CloudTest.Answer(503, m("error", "host_failed", "message", "down")),
        new CloudTest.Answer(409, m("error", "conflict", "message", "no")),
        new CloudTest.Answer(503, m("error", "maintenance", "message", "later", "retryable", false))));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> answers.remove(0))) {
      Kosa8Cloud cloud = stub.cloud(new ArrayList<>());
      raises(Kosa8Exception.class, () -> cloud.functions().invoke("f"));
      eq(1, stub.seen.size(), "a 503 that may have run is not resent");
      raises(Kosa8Exception.ConflictException.class, () -> cloud.webhooks().test("wh_1"));
      eq(2, stub.seen.size(), "a plain 409 is not retried");
      raises(Kosa8Exception.class, cloud::limits);
      eq(3, stub.seen.size(), "retryable: false is honoured even on a GET");
    }
  }

  public void testJobSubmitIsKeyedAndRetried() throws Exception {
    List<CloudTest.Answer> answers = new ArrayList<>(List.of(
        new CloudTest.Answer(502, m("error", "host_failed", "message", "x")),
        new CloudTest.Answer(202, m("job", m("status", "queued"), "job_id", "job_1", "run_id", "run_1"))));
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> answers.remove(0))) {
      Map<String, Object> spec = m("name", "j", "template", "python", "tasks", List.of(m("id", "t1")),
          "solver", m("cmd", "true", "timeout_s", 60), "grader", m("cmd", "true"));
      Map<String, Object> job = stub.cloud(new ArrayList<>()).jobs().submit(spec);
      eq("job_1", job.get("id"), "id from job_id");
      eq("run_1", job.get("run_id"), "run_id");
      check(stub.seen.get(0).headers().get("idempotency-key") != null, "keyed");
      check(stub.seen.get(1).headers().get("idempotency-key") != null
          && !stub.seen.get(0).headers().get("idempotency-key").equals(stub.seen.get(1).headers().get("idempotency-key")),
          "a new key on the retry after a 502");
      raises(IllegalArgumentException.class, () -> stub.cloud().jobs().submit(m("name", "j", "template", "a", "image", "b",
          "solver", m(), "grader", m())));
    }
  }

  public void testJobsRoutes() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> ok(m("job", m("id", "job_1", "status", "done"), "jobs", List.of(), "sandbox_hours", m())))) {
      Kosa8Cloud c = stub.cloud();
      Map<String, Object> spec = m("name", "j", "snapshot", "snap_12345678", "tasks", List.of(m("id", "t")),
          "solver", m("cmd", "x", "timeout_s", 5), "grader", m("cmd", "y"));
      c.jobs().estimate(spec, 5, null);
      eq("/v1/jobs/estimate", last(stub).path(), "estimate");
      eq(5L, ((Map<?, ?>) last(stub).body()).get("sample"), "sample");
      c.jobs().get("job_1");
      eq("/v1/jobs/job_1", last(stub).path(), "get");
      c.jobs().cancel("job_1");
      eq("POST /v1/jobs/job_1/cancel", last(stub).method() + " " + last(stub).path(), "cancel");
      c.jobs().list("running", "p", 10, null);
      eq("/v1/jobs?status=running&project=p&limit=10", last(stub).path(), "list");
      eq("done", c.jobs().waitFor("job_1", Duration.ofSeconds(5), Duration.ZERO).get("status"), "wait");
    }
  }

  public void testSmallSizes() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> new CloudTest.Answer(201, m("sandbox", m("id", "sbx_1", "state", "running"))))) {
      Kosa8Cloud c = stub.cloud();
      c.sandboxes().create(new CloudOptions.Create().cpus(0.25, 512).ttlSeconds(600));
      eq(m("ttl_s", 600L, "cpus", 0.25, "mem_mb", 512L), last(stub).body(), "fractional cpus");
      c.sandboxes().create(new CloudOptions.Create().size("0.25x0.5"));
      eq(m("size", "0.25x0.5"), last(stub).body(), "small size");
      c.sandboxes().create(new CloudOptions.Create().cpus(2, 4096).record(true).aiGateway(true));
      eq(m("cpus", 2L, "mem_mb", 4096L, "record", true, "ai_gateway", true), last(stub).body(), "whole cpus, record, ai_gateway");
      raises(IllegalArgumentException.class, () -> c.sandboxes().create(new CloudOptions.Create().size("3x3")));
      raises(IllegalArgumentException.class, () -> c.sandboxes().create(new CloudOptions.Create().cpus(0, 512)));
    }
  }

  public void testOverflowRefusalIsUnsupported() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> new CloudTest.Answer(501, m("error", "not_available",
        "message", "Fork is not available for this sandbox: it runs on kosa8's overflow capacity (Cloudflare)", "failure_class", "infra",
        "retryable", false)))) {
      CloudSandbox b = new CloudSandbox(stub.cloud(new ArrayList<>()), m("id", "sbx_1", "state", "running"));
      Kosa8Exception.UnsupportedException e = raises(Kosa8Exception.UnsupportedException.class, () -> b.fork(1));
      eq("not_available", e.code(), "code");
      check(e.getMessage().contains("overflow capacity"), e.getMessage());
      eq(1, stub.seen.size(), "not retried");
    }
  }

  public void testBrowsers() throws Exception {
    String png = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().endsWith("/page")) return ok(m("page", m("url", "https://example.com", "title", "Example", "screenshot_png_base64", png)));
      if (s.method().equals("DELETE")) return ok(m("ok", true, "id", "brw_1"));
      if (s.path().startsWith("/v1/browsers?") || s.path().equals("/v1/browsers") && s.method().equals("GET")) {
        return ok(m("browsers", List.of(m("id", "brw_1", "state", "open")), "enabled", true, "limit", 5, "price_per_hour_usd", 0.12));
      }
      return ok(m("browser", m("id", "brw_1", "state", "open", "cdp_url", "wss://api.kosa8.com/v1/browsers/brw_1/cdp?token=t")));
    })) {
      Kosa8Cloud c = stub.cloud();
      try (CloudBrowser b = c.browsers().create(120, null, "agent")) {
        eq(m("ttl_s", 120L, "name", "agent"), last(stub).body(), "create body");
        eq("wss://api.kosa8.com/v1/browsers/brw_1/cdp?token=t", b.cdpUrl(), "cdp url");
        check(!b.toString().contains("token"), "toString hides the cdp token");
        byte[] shot = b.screenshot("https://example.com", true);
        eq(4, shot.length, "png decoded");
        eq(m("url", "https://example.com", "screenshot", true, "full_page", true), last(stub).body(), "page body");
        eq("/v1/browsers/brw_1/page", last(stub).path(), "page path");
      }
      eq("DELETE /v1/browsers/brw_1", last(stub).method() + " " + last(stub).path(), "closed on the way out");
      eq(1, c.browsers().list("all").size(), "list");
      eq("/v1/browsers?state=all", last(stub).path(), "list path");
      eq("wss://api.kosa8.com/v1/browsers/brw_1/cdp?token=t", c.browsers().cdpUrl("brw_1"), "fresh cdp url");
      eq("/v1/browsers/brw_1/cdp", CloudBrowsers.cdpPath("brw_1"), "cdp path");
      raises(IllegalArgumentException.class, () -> c.browsers().create(10, null, null));
    }
  }

  public void testAiGateway() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> ok(m("keys", List.of(m("provider", "openai", "hint", "abcd")), "ok", true)))) {
      Kosa8Cloud c = stub.cloud();
      eq(stub.url() + "/v1/ai/gw/openai/", c.ai().gatewayUrl("openai"), "gateway base url");
      c.ai().setKey("anthropic", "sk-ant-x");
      eq("PUT /v1/ai/keys/anthropic", last(stub).method() + " " + last(stub).path(), "set key");
      eq(1, c.ai().keys().size(), "keys");
      c.ai().deleteKey("openai");
      eq("DELETE /v1/ai/keys/openai", last(stub).method() + " " + last(stub).path(), "delete key");
      c.ai().updateSettings(true, null, 60, null);
      eq(m("enabled", true, "cache_ttl_s", 60L), last(stub).body(), "patch body");
      eq("PATCH", last(stub).method(), "patch");
      c.ai().usage("2026-11");
      eq("/v1/ai/usage?month=2026-11", last(stub).path(), "usage");
      c.ai().call("openai", "chat/completions", m("model", "m"));
      eq("/v1/ai/gw/openai/chat/completions", last(stub).path(), "gateway call");
      raises(IllegalArgumentException.class, () -> c.ai().usage("2026-13"));
      raises(IllegalArgumentException.class, () -> c.ai().gatewayUrl("../x"));
    }
  }

  public void testBenchmarksRecordingsEmbedsDomains() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> ok(m("benchmarks", List.of(m("name", "swe")), "instance", m("id", "i1"),
        "recordings", List.of(m("id", "rec_1")), "link", m("url", "https://x"), "embed", m("id", "emb_1", "url", "https://e"),
        "embeds", List.of(), "domains", List.of(m("hostname", "a.example.com")), "domain", m("hostname", "a.example.com"))))) {
      Kosa8Cloud c = stub.cloud();
      eq(1, c.benchmarks().list().size(), "benchmarks");
      c.benchmarks().instance("swe-bench-verified", "django__django-1");
      eq("/v1/benchmarks/swe-bench-verified/instances/django__django-1", last(stub).path(), "instance");
      c.benchmarks().instances("swe", "ready", 10, null);
      eq("/v1/benchmarks/swe/instances?state=ready&limit=10", last(stub).path(), "instances");
      c.benchmarks().prepare("swe", List.of("a", "b"));
      eq(m("instances", List.of("a", "b")), last(stub).body(), "prepare");
      c.benchmarks().prepare("swe", 5, 7);
      eq(m("instances", 5L, "seed", 7L), last(stub).body(), "prepare n");
      eq(1, c.recordings().list().size(), "recordings");
      c.recordings().link("rec_1", 600);
      eq("/v1/recordings/rec_1/link", last(stub).path(), "link");
      c.recordings().delete("rec_1");
      eq("DELETE /v1/recordings/rec_1", last(stub).method() + " " + last(stub).path(), "delete recording");
      CloudSandbox b = new CloudSandbox(c, m("id", "sbx_1", "state", "running"));
      eq(1, b.recordings().size(), "sandbox recordings");
      eq("/v1/sandboxes/sbx_1/recordings", last(stub).path(), "sandbox recordings path");
      eq("https://e", b.embeds().create(List.of("https://app.example.com"), "interact", null, 600, null).get("url"), "embed");
      eq("/v1/sandboxes/sbx_1/embed", last(stub).path(), "embed path");
      b.embeds().list();
      eq("/v1/sandboxes/sbx_1/embeds", last(stub).path(), "embeds path");
      raises(IllegalArgumentException.class, () -> b.embeds().create(List.of(), null, null, null, null));
      eq(1, b.previews().domains(3000).list().size(), "domains");
      eq("/v1/sandboxes/sbx_1/previews/3000/domains", last(stub).path(), "domains path");
      b.previews().domains(3000).add("a.example.com", "txt");
      eq(m("hostname", "a.example.com", "ssl_method", "txt"), last(stub).body(), "add domain");
      c.previewDomains("prv_1").remove("a.example.com");
      eq("DELETE /v1/previews/prv_1/domains/a.example.com", last(stub).method() + " " + last(stub).path(), "remove by preview id");
      b.previews().delete(3000);
      eq("DELETE /v1/sandboxes/sbx_1/previews/3000", last(stub).method() + " " + last(stub).path(), "preview delete");
    }
  }

  public void testOrgAndAccountRoutes() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().startsWith("/v1/audit/export")) {
        return ok("{\"seq\":1,\"action\":\"a\"}\n{\"seq\":2,\"action\":\"b\"}\n".getBytes(StandardCharsets.UTF_8));
      }
      if (s.path().equals("/v1/org/egress") && s.method().equals("GET")) return ok(m("egress", null));
      return ok(m("api_keys", List.of(m("id", "key_1")), "api_key", m("id", "key_2", "secret", "k8c_live_s"), "images", List.of(m("image", "x")),
          "invocations", List.of(m("id", "inv_1")), "alerts", List.of(), "alert", m("id", "rla_1"), "events", List.of(), "exec",
          m("id", "bg_1", "state", "stopping")));
    })) {
      Kosa8Cloud c = stub.cloud();
      eq(null, c.orgEgress(), "no org policy");
      c.setOrgEgress(false, List.of("pypi.org"), null, null, true);
      eq(m("default", m("internet", false, "allow", List.of("pypi.org"), "deny", List.of(), "presets", List.of()), "enforce", true),
          last(stub).body(), "org egress body");
      c.activateBurst(2);
      eq(m("quantity", 2L), last(stub).body(), "burst");
      c.requestLimit(50, "eval run");
      eq("/v1/limits/request", last(stub).path(), "limit request");
      c.limits();
      c.pricing();
      c.usageInsights();
      c.lineage();
      c.checkpoints();
      eq(1, c.images().size(), "images");
      eq(2, c.audit().export(Map.of("action", "a")).size(), "ndjson export");
      eq("/v1/audit/export?action=a", last(stub).path(), "export path");
      c.audit().verify();
      c.audit().list(Map.of("limit", 5));
      eq("/v1/audit?limit=5", last(stub).path(), "audit list");
      eq(1, c.apiKeys().list().size(), "keys");
      eq("k8c_live_s", c.apiKeys().create("ci", List.of("sandboxes:write"), null).get("secret"), "key secret");
      c.apiKeys().rotate("key_1", 3600);
      eq("POST /v1/api-keys/key_1/rotate", last(stub).method() + " " + last(stub).path(), "rotate");
      c.apiKeys().revoke("key_1");
      eq("DELETE /v1/api-keys/key_1", last(stub).method() + " " + last(stub).path(), "revoke");
      c.webhooks().deliveries("wh_1", 20);
      eq("/v1/webhooks/wh_1/deliveries?limit=20", last(stub).path(), "deliveries");
      c.webhooks().delete("wh_1");
      eq("DELETE /v1/webhooks/wh_1", last(stub).method() + " " + last(stub).path(), "webhook delete");
      eq(1, c.functions().invocations("f", 5).size(), "invocations");
      eq("/v1/functions/f/invocations?limit=5", last(stub).path(), "invocations path");
      c.rl().compare(List.of("run_1", "run_2"));
      eq("/v1/rl/compare?ids=run_1%2Crun_2", last(stub).path(), "compare");
      c.rl().createAlert("run_1", "reward_drop", 0.2, 50);
      eq(m("run_id", "run_1", "kind", "reward_drop", "threshold", 0.2, "window", 50L), last(stub).body(), "alert body");
      c.rl().updateAlert("rla_1", null, null, false);
      eq("PATCH /v1/rl/alerts/rla_1", last(stub).method() + " " + last(stub).path(), "alert patch");
      raises(IllegalArgumentException.class, () -> c.rl().compare(List.of("one")));
      CloudSandbox b = new CloudSandbox(c, m("id", "sbx_1", "state", "running"));
      eq("stopping", b.stopBackground("bg_1").state(), "stop");
      eq("POST /v1/sandboxes/sbx_1/exec/bg_1/stop", last(stub).method() + " " + last(stub).path(), "stop path");
      b.schedules().delete("sch_1");
      eq("DELETE /v1/sandboxes/sbx_1/schedules/sch_1", last(stub).method() + " " + last(stub).path(), "schedule delete");
      b.deleteCodeContext("main");
      eq("DELETE /v1/sandboxes/sbx_1/code/contexts/main", last(stub).method() + " " + last(stub).path(), "context delete");
      b.embeds().revoke("emb_1");
      eq("DELETE /v1/sandboxes/sbx_1/embeds/emb_1", last(stub).method() + " " + last(stub).path(), "embed revoke");
      c.uploadAttackScorecard("{\"v\":1}");
      eq(m("signed", m("v", 1L)), last(stub).body(), "scorecard upload");
      c.attackScorecards(5);
      eq("/v1/attack/scorecards?limit=5", last(stub).path(), "scorecards");
      raises(IllegalArgumentException.class, () -> c.uploadAttackScorecard("not json"));
    }
  }
}
