package com.kosa8;

import com.kosa8.CloudTest.Answer;
import com.kosa8.CloudTest.Seen;
import com.kosa8.CloudTest.Stub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Fan-out, retries, idempotency, the sealed grader, forkMap, RL runs, org templates and tokens, against a local stub. */
public final class RlTest extends ClientTest {
  static final String KEY = CloudTest.KEY;

  static Map<String, Object> box(String id) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("state", "running");
    return m;
  }

  static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
    return out;
  }

  static List<Seen> seen(Stub s, String method, String pathPrefix) {
    List<Seen> out = new ArrayList<>();
    synchronized (s.seen) {
      for (Seen x : s.seen) if (x.method().equals(method) && x.path().startsWith(pathPrefix)) out.add(x);
    }
    return out;
  }

  // --- failure classes and errors ------------------------------------------------------

  public void testFailureClassFollowsTheServerRules() {
    eq(new FailureClass("ok", false), FailureClass.of(200, null), "2xx");
    eq(new FailureClass("timeout", false), FailureClass.of(504, "exec_timeout"), "exec_timeout");
    eq(new FailureClass("timeout", true), FailureClass.of(502, "host_timeout"), "other timeout");
    eq(new FailureClass("quota", true), FailureClass.of(429, "rate_limited"), "rate limit");
    eq(new FailureClass("quota", false), FailureClass.of(429, "quota_exceeded"), "quota");
    eq(new FailureClass("quota", false), FailureClass.of(402, null), "402");
    eq(new FailureClass("quota", false), FailureClass.of(423, "org_frozen"), "423");
    eq(new FailureClass("infra", false), FailureClass.of(501, "not_available"), "501");
    eq(new FailureClass("infra", true), FailureClass.of(503, "no_capacity"), "503");
    eq(new FailureClass("infra", true), FailureClass.of(409, "host_busy"), "infra code on 409");
    eq(new FailureClass("client", false), FailureClass.of(400, "invalid_count"), "400");
    eq(new FailureClass("ok", false), FailureClass.ofExit(0), "exit 0");
    eq(new FailureClass("exit_nonzero", false), FailureClass.ofExit(2), "exit 2");
    eq(new FailureClass("infra", true), FailureClass.ofExit(null), "no exit");
    eq(new FailureClass("error", false), Kosa8Exception.classifyFailure(new IllegalStateException("bug")), "your bug");
    eq(new FailureClass("infra", true), Kosa8Exception.classifyFailure(new java.io.IOException("reset")), "io");
    eq(new FailureClass("infra", true), Kosa8Exception.classifyFailure(new Kosa8Exception.ConnectionException("x", "GET", "/", null)), "conn");
    var inProgress = Kosa8Cloud.cloudError(409, m("error", "idempotency_in_progress", "message", "wait", "retry_after_s", 2), "POST", "/v1/sandboxes");
    eq(new FailureClass("client", true), Kosa8Exception.classifyFailure(inProgress), "in progress is retryable");
    eq(2.0, inProgress.retryAfterSeconds(), "retry_after_s");
    var sent = Kosa8Cloud.cloudError(409, m("error", "conflict", "failure_class", "infra", "retryable", true), "POST", "/v1/x");
    eq("infra", sent.failureClass(), "body's class used as sent");
    check(sent.retryable(), "body's retryable used as sent");
  }

  public void testErrorsAndExecCarryFailureClass() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(503, m("error", "no_capacity", "message", "no room", "failure_class", "infra", "retryable", true, "retry_after_s", 5)),
        new Answer(429, m("error", "rate_limited", "message", "slow"), Map.of("Retry-After", "7")),
        new Answer(200, m("exit", 1, "stdout", "", "stderr", "boom", "failure_class", "exit_nonzero", "retryable", false)),
        new Answer(200, m("stdout", "", "stderr", "")),
        new Answer(200, m("exit", 0, "stdout", "hi", "stderr", ""))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox b = new CloudSandbox(cloud, box("sbx_1"));
      var e = raises(Kosa8Exception.class, () -> cloud.sandboxes().create());
      eq("infra", e.failureClass(), "503 class");
      check(e.retryable(), "503 retryable");
      eq(5.0, e.retryAfterSeconds(), "body retry_after_s");
      var r = raises(Kosa8Exception.RateLimitException.class, () -> cloud.me());
      eq(7.0, r.retryAfterSeconds(), "Retry-After header as seconds");
      eq("quota", r.failureClass(), "429 class");
      check(r.retryable(), "rate limit retryable");
      CloudExecResult x = b.exec("false");
      eq("exit_nonzero", x.failureClass(), "exec class");
      check(!x.retryable(), "exit_nonzero not retryable");
      CloudExecResult y = b.exec("kill -9 $$");
      eq("infra", y.failureClass(), "no exit is infra");
      check(y.retryable(), "infra retryable");
      eq(new CloudExecResult(0, "hi", "", null), b.exec("echo hi"), "0.4 constructor still equal");
    }
  }

  // --- retries and idempotency -----------------------------------------------------------

  public void testRetryPolicyBackoffJitterAndRetryAfter() {
    RetryPolicy p = RetryPolicy.DEFAULT;
    var infra = Kosa8Cloud.cloudError(503, m("error", "host_failed"), "GET", "/v1/me");
    for (int i = 0; i < 50; i++) {
      long d1 = p.delay(infra, 1).toMillis(), d4 = p.delay(infra, 4).toMillis(), d9 = p.delay(infra, 9).toMillis();
      check(d1 >= 0 && d1 <= 500, "attempt 1 at most base: " + d1);
      check(d4 <= 4000, "attempt 4 at most 4 s: " + d4);
      check(d9 <= 8000, "capped at max: " + d9);
    }
    var ra = Kosa8Cloud.cloudError(429, m("error", "rate_limited"), "GET", "/v1/me", "30");
    check(p.delay(ra, 1).toMillis() >= 30_000, "at least Retry-After");
    var big = Kosa8Cloud.cloudError(429, m("error", "rate_limited"), "GET", "/v1/me", "600");
    eq(60_000L, p.delay(big, 1).toMillis(), "Retry-After capped at maxRetryAfter");
    check(p.shouldRetry(infra, 1) && p.shouldRetry(infra, 2) && !p.shouldRetry(infra, 3), "3 attempts");
    check(!p.shouldRetry(Kosa8Cloud.cloudError(400, m("error", "invalid"), "GET", "/"), 1), "client not retried");
    check(!RetryPolicy.NONE.shouldRetry(infra, 1), "NONE");
    eq(5, RetryPolicy.attempts(5).attempts(), "attempts()");
    raises(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO, Duration.ZERO));
  }

  public void testKeyedCreateRetriesWithANewIdempotencyKeyAfterA5xx() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(503, m("error", "host_failed", "message", "down")),
        new Answer(201, m("sandbox", box("sbx_1")))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      Kosa8Cloud cloud = stub.cloud(sleeps);
      eq("sbx_1", cloud.sandboxes().create(new CloudOptions.Create().leaseS(600)).id(), "made once retried");
      eq(2, stub.seen.size(), "two tries");
      String k1 = stub.seen.get(0).headers().get("idempotency-key"), k2 = stub.seen.get(1).headers().get("idempotency-key");
      check(k1 != null && k1.length() == 36, "a UUID key: " + k1);
      check(k2 != null && k2.length() == 36 && !k1.equals(k2), "a new key after a 5xx: " + k1 + " " + k2);
      eq(600L, ((Map<?, ?>) stub.seen.get(0).body()).get("lease_s"), "lease_s sent");
      eq(1, sleeps.size(), "one wait");
    }
  }

  static List<String> keys(Stub stub) {
    List<String> out = new ArrayList<>();
    synchronized (stub.seen) { for (Seen s : stub.seen) out.add(s.headers().get("idempotency-key")); }
    return out;
  }

  public void testA502RetryGetsANewKeyAndA503WaitsItsRetryAfter() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(502, m("error", "host_failed", "message", "boot failed", "failure_class", "infra", "retryable", true)),
        new Answer(503, m("error", "host_restarting", "message", "restarting"), Map.of("Retry-After", "2")),
        new Answer(201, m("sandbox", box("sbx_1")))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      stub.cloud(sleeps).sandboxes().create(new CloudOptions.Create());
      List<String> k = keys(stub);
      eq(3, k.size(), "three tries");
      eq(3, (int) k.stream().distinct().count(), "a new key on each retry after a 5xx: " + k);
      check(sleeps.get(1).toMillis() >= 2000, "waited Retry-After: " + sleeps);
    }
  }

  public void testNoAnswerKeepsTheKey() throws Exception {
    List<Answer> answers = new ArrayList<>();
    answers.add(null); // the connection drops: no answer
    answers.add(new Answer(201, m("sandbox", box("sbx_1"))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      eq("sbx_1", stub.cloud(new ArrayList<>()).sandboxes().create(new CloudOptions.Create()).id(), "made");
      List<String> k = keys(stub);
      eq(2, k.size(), "two tries");
      check(k.get(0) != null && k.get(0).equals(k.get(1)), "the same key after no answer: " + k);
    }
  }

  public void testA429AndA409InProgressKeepTheKey() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(429, m("error", "rate_limited", "message", "slow"), Map.of("Retry-After", "1")),
        new Answer(409, m("error", "idempotency_in_progress", "message", "wait", "retry_after_s", 1)),
        new Answer(201, m("sandbox", box("sbx_1")))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      stub.cloud(new ArrayList<>()).sandboxes().create(new CloudOptions.Create());
      List<String> k = keys(stub);
      eq(3, k.size(), "three tries");
      eq(1, (int) k.stream().distinct().count(), "one key: " + k);
    }
  }

  public void testTheCallersKeyIsNeverChanged() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(502, m("error", "host_failed", "message", "boot failed")),
        new Answer(201, m("sandbox", box("sbx_1")))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      stub.cloud(new ArrayList<>()).sandboxes().create(new CloudOptions.Create().idempotencyKey("caller-key"));
      eq(List.of("caller-key", "caller-key"), keys(stub), "the caller's key on every try");
    }
    check(Kosa8Cloud.renewsIdempotencyKey(Kosa8Cloud.cloudError(504, m("error", "gateway_timeout"), "POST", "/v1/sandboxes")), "504");
    check(!Kosa8Cloud.renewsIdempotencyKey(Kosa8Cloud.cloudError(429, m("error", "rate_limited"), "POST", "/v1/sandboxes")), "429");
    check(!Kosa8Cloud.renewsIdempotencyKey(new Kosa8Exception.ConnectionException("x", "POST", "/v1/sandboxes", null)), "no answer");
  }

  public void testRetryScopeAnd429() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(429, m("error", "rate_limited", "message", "slow"), Map.of("Retry-After", "3")),
        new Answer(200, m("ok", true)),
        new Answer(503, m("error", "host_failed", "message", "down")),
        new Answer(400, m("error", "invalid", "message", "bad")),
        new Answer(502, m("error", "host_failed", "message", "down")),
        new Answer(200, m("org", m("id", "org_1"))),
        new Answer(429, m("error", "quota_exceeded", "message", "full"))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      List<Duration> sleeps = new ArrayList<>();
      Kosa8Cloud cloud = stub.cloud(sleeps);
      eq(true, cloud.functions().invoke("f").get("ok"), "an unkeyed POST is retried on 429");
      check(sleeps.get(0).toMillis() >= 3000, "waited at least Retry-After: " + sleeps);
      raises(Kosa8Exception.class, () -> cloud.functions().invoke("f"));
      eq(3, stub.seen.size(), "an unkeyed POST is not retried on a 5xx");
      raises(Kosa8Exception.BadRequestException.class, cloud::me);
      eq(4, stub.seen.size(), "a 400 is not retried");
      eq("org_1", ((Map<?, ?>) cloud.me().get("org")).get("id"), "a GET is retried on 502");
      eq(6, stub.seen.size(), "502 then 200");
      raises(Kosa8Exception.RateLimitException.class, () -> cloud.sandboxes().list());
      eq(7, stub.seen.size(), "a used-up quota is not retried");
      raises(IllegalArgumentException.class, () -> new CloudOptions.Create().idempotencyKey("has space"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Create().idempotencyKey(""));
    }
  }

  public void testCreateLeaseRules() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(201, m("sandbox", m("id", "sbx_1", "state", "running", "lease_s", 600))))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox b = cloud.sandboxes().create(new CloudOptions.Create().leaseS(600).idempotencyKey("my-key-1"));
      eq("my-key-1", stub.seen.get(0).headers().get("idempotency-key"), "caller's key");
      eq(600, b.leaseS(), "leaseS");
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().leaseS(600).ttlSeconds(3600)));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().leaseS(30)));
      cloud.me();
      eq(null, stub.seen.get(1).headers().get("idempotency-key"), "a GET carries no key");
    }
  }

  // --- batch, fork, heartbeat, drain, grade --------------------------------------------

  public void testCreateManyBatch() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(200, m("sandboxes", List.of(box("sbx_a"), box("sbx_b")),
        "failed", List.of(m("index", 2, "code", "running_limit", "message", "past the limit", "failure_class", "quota", "retryable", false)),
        "requested", 3, "created", 2, "run_id", "run-1")))) {
      Kosa8Cloud cloud = stub.cloud();
      SandboxBatch b = cloud.sandboxes().createMany(3, new CloudOptions.Batch().create(new CloudOptions.Create().template("python").size("2x4"))
          .labels(Map.of("team", "rl")).runId("run-1").leaseS(600).prefix("roll").partialOk(true).idempotencyKey("batch-1"));
      Seen s = stub.seen.get(0);
      eq("POST /v1/sandboxes/batch", s.method() + " " + s.path(), "route");
      eq("batch-1", s.headers().get("idempotency-key"), "key");
      Map<?, ?> body = (Map<?, ?>) s.body();
      eq(3L, body.get("count"), "count");
      eq("python", body.get("template"), "create fields");
      eq("2x4", body.get("size"), "size");
      eq(Map.of("team", "rl"), body.get("labels"), "labels");
      eq("run-1", body.get("run_id"), "run_id");
      eq(600L, body.get("lease_s"), "lease_s");
      eq("roll", body.get("prefix"), "prefix");
      eq(true, body.get("partial_ok"), "partial_ok");
      eq(List.of("sbx_a", "sbx_b"), b.ids(), "ids");
      eq(3, b.requested(), "requested");
      eq(2, b.created(), "created");
      eq("run-1", b.runId(), "runId");
      check(!b.complete(), "not complete");
      FanoutFailure f = b.failed().get(0);
      eq(2, f.index(), "failed index");
      eq("running_limit", f.code(), "failed code");
      eq("quota", f.failureClass(), "failed class");
      check(!f.retryable(), "not retryable");
      cloud.sandboxes().createMany(1);
      check(stub.seen.get(1).headers().get("idempotency-key") != null, "a default key");
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().createMany(0));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().createMany(1001));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().createMany(2, new CloudOptions.Batch().create(new CloudOptions.Create().name("x"))));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Batch().runId("-bad"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Batch().prefix("bad prefix"));
      eq(2, stub.seen.size(), "nothing sent for the bad ones");
    }
  }

  public void testForkOptions() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(201, m("parent", "sbx_1", "sandboxes", List.of(box("sbx_c1")), "requested", 250, "created", 1,
        "failed", List.of(m("index", 1, "code", "no_capacity", "message", "host full", "failure_class", "infra", "retryable", true)))))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox b = new CloudSandbox(cloud, box("sbx_1"));
      SandboxBatch r = b.fork(new CloudOptions.Fork().n(250).labels(Map.of("k", "v")).runId("run-1").leaseS(900).prefix("c").partialOk(true));
      Map<?, ?> body = (Map<?, ?>) stub.seen.get(0).body();
      eq(m("n", 250L, "labels", Map.of("k", "v"), "run_id", "run-1", "lease_s", 900L, "prefix", "c", "partial_ok", true), body, "fork body");
      check(stub.seen.get(0).headers().get("idempotency-key") != null, "fork carries a key");
      eq(List.of("sbx_c1"), r.ids(), "made");
      eq("infra", r.failed().get(0).failureClass(), "failed infra");
      eq(250, r.requested(), "requested");
      raises(IllegalArgumentException.class, () -> new CloudOptions.Fork().n(1001));
      raises(IllegalArgumentException.class, () -> b.fork(101));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Fork().labels(manyLabels(16)).runId("r").body());
    }
  }

  static Map<String, String> manyLabels(int n) {
    Map<String, String> l = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) l.put("k" + i, "v");
    return l;
  }

  public void testHeartbeatAndDrainLoop() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(200, m("extended", 7, "at", 1)),
        new Answer(200, m("extended", 1, "at", 1)),
        new Answer(200, m("deleted", 1000, "ids", List.of("sbx_a"), "failed", List.of(), "more", true)),
        new Answer(200, m("deleted", 3, "ids", List.of("sbx_b"), "failed", List.of(m("id", "sbx_z", "code", "host_failed", "message", "x",
            "failure_class", "infra", "retryable", true)), "more", true)),
        new Answer(200, m("deleted", 0, "ids", List.of(), "failed", List.of(), "more", true)),
        new Answer(200, m("deleted", 2, "ids", List.of("sbx_q"), "failed", List.of(), "more", false))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      Kosa8Cloud cloud = stub.cloud();
      eq(7, cloud.sandboxes().heartbeat(new CloudOptions.Selector().runId("run-1").labels(Map.of("a", "b")), 120), "extended");
      eq(m("run_id", "run-1", "labels", Map.of("a", "b"), "extend_s", 120L), stub.seen.get(0).body(), "heartbeat body");
      check(new CloudSandbox(cloud, box("sbx_1")).heartbeat(), "sandbox heartbeat");
      eq(m("ids", List.of("sbx_1")), stub.seen.get(1).body(), "by id");
      DrainResult d = cloud.sandboxes().drain("run-1");
      eq(1003, d.deleted(), "summed until a call deleted none");
      eq(List.of("sbx_a", "sbx_b"), d.ids(), "ids");
      eq("sbx_z", d.failed().get(0).id(), "failed id");
      eq(5, stub.seen.size(), "three drain calls");
      eq(2, cloud.sandboxes().drain(CloudOptions.Selector.ids(List.of("sbx_q"))).deleted(), "drain by ids");
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().drain(new CloudOptions.Selector()));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().heartbeat(CloudOptions.Selector.run("r"), 10));
      raises(IllegalArgumentException.class, () -> CloudOptions.Selector.ids(List.of("bad id")));
    }
  }

  public void testSealedGrade() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(200, m("reward", 0.5, "metrics", m("passed", 1), "reward_from", "json", "exit", 0, "stdout", "{\"reward\":0.5}",
        "stderr", "", "failure_class", "ok", "retryable", false, "ms", 812, "grader_id", "sbx_g", "kept", false, "sandbox_id", "sbx_1",
        "run_id", "run-1", "recorded", true)))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox b = new CloudSandbox(cloud, box("sbx_1"));
      GradeResult g = b.grade(List.of("pytest", "-q", "/grader/t.py"), new CloudOptions.Grade().file("/grader/t.py", "assert 1")
          .timeoutS(60).env(Map.of("A", "1")).runId("run-1").rollout(3, "7", 1, Map.of("task", "x")).idempotencyKey("g-1"));
      Seen s = stub.seen.get(0);
      eq("POST /v1/sandboxes/sbx_1/grade", s.method() + " " + s.path(), "route");
      eq("g-1", s.headers().get("idempotency-key"), "key");
      Map<?, ?> body = (Map<?, ?>) s.body();
      eq(List.of("pytest", "-q", "/grader/t.py"), body.get("cmd"), "cmd");
      eq(Map.of("/grader/t.py", Base64.getEncoder().encodeToString("assert 1".getBytes(StandardCharsets.UTF_8))), body.get("files"), "files base64");
      eq(60L, body.get("timeout_s"), "timeout");
      eq(List.of("A=1"), body.get("env"), "env");
      eq("run-1", body.get("run_id"), "run_id");
      eq(m("step", 3L, "group", "7", "index", 1L, "meta", Map.of("task", "x")), body.get("rollout"), "rollout");
      eq(0.5, g.reward(), "reward");
      eq("json", g.rewardFrom(), "reward_from");
      eq(0, g.exitCode(), "exit");
      eq("ok", g.failureClass(), "class");
      eq("sbx_g", g.graderId(), "grader");
      eq(true, g.recorded(), "recorded");
      eq(812L, g.ms(), "ms");
      check(g.finished(), "finished");
      cloud.sandboxes().grade("sbx_9", List.of("true"), null);
      eq("/v1/sandboxes/sbx_9/grade", stub.seen.get(1).path(), "grade by id");
      raises(IllegalArgumentException.class, () -> b.grade(List.of()));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Grade().timeoutS(91));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Grade().file("relative", "x"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Grade().file("/a/../b", "x"));
      CloudOptions.Grade many = new CloudOptions.Grade();
      for (int i = 0; i < 65; i++) many.file("/g/" + i, "x");
      raises(IllegalArgumentException.class, () -> b.grade(List.of("true"), many));
      raises(IllegalArgumentException.class, () -> b.grade(List.of("true"), new CloudOptions.Grade().file("/big", new byte[8 * 1024 * 1024 + 1])));
      eq(2, stub.seen.size(), "nothing sent for the bad ones");
    }
  }

  // --- forkMap -------------------------------------------------------------------------

  /** A stub that forks children sbx_c1, sbx_c2, ... and records deletes. */
  static final class Forker {
    final AtomicInteger next = new AtomicInteger();
    final Set<String> deleted = java.util.concurrent.ConcurrentHashMap.newKeySet();
    final Set<String> made = java.util.concurrent.ConcurrentHashMap.newKeySet();

    Answer answer(Seen s) {
      if (s.path().endsWith("/fork")) {
        int n = ((Number) ((Map<?, ?>) s.body()).get("n")).intValue();
        List<Object> kids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
          String id = "sbx_c" + next.incrementAndGet();
          made.add(id);
          kids.add(box(id));
        }
        return new Answer(201, m("sandboxes", kids, "requested", n, "created", n, "failed", List.of()));
      }
      if (s.method().equals("DELETE")) {
        deleted.add(s.path().substring(s.path().lastIndexOf('/') + 1));
        return new Answer(200, m("ok", true));
      }
      if (s.path().equals("/v1/sandboxes/drain")) {
        List<?> ids = (List<?>) ((Map<?, ?>) s.body()).get("ids");
        for (Object id : ids) deleted.add(String.valueOf(id));
        return new Answer(200, m("deleted", ids.size(), "ids", ids, "failed", List.of(), "more", false));
      }
      return null;
    }
  }

  public void testForkMapRetriesReportsAndCleansUp() throws Exception {
    Forker f = new Forker();
    try (Stub stub = new Stub(s -> { Answer x = f.answer(s); return x != null ? x : new Answer(404, m("error", "not_found")); })) {
      List<Duration> sleeps = new ArrayList<>();
      Kosa8Cloud cloud = stub.cloud(sleeps);
      CloudSandbox base = new CloudSandbox(cloud, box("sbx_base"));
      AtomicInteger flaky = new AtomicInteger();
      List<ForkResult<Integer, Integer>> rs = cloud.forkMap(base, List.of(1, 2, 3, 4), (child, x) -> {
        if (x == 2) throw new IllegalStateException("bug in fn");
        if (x == 3 && flaky.getAndIncrement() == 0) throw ForkMap.classified("host went away", "infra", true);
        return x * 10;
      }, new CloudOptions.ForkMap().concurrency(2).retries(2).runId("run-1").leaseS(600));
      eq(4, rs.size(), "one result per item");
      eq(10, rs.get(0).value(), "in order");
      eq(40, rs.get(3).value(), "in order 4");
      check(rs.get(0).ok(), "ok");
      eq("error", rs.get(1).failureClass(), "your exception: error");
      eq(1, rs.get(1).attempts(), "not retried");
      check(rs.get(1).error() instanceof IllegalStateException, "error kept");
      eq(30, rs.get(2).value(), "retried on a fresh child");
      eq(2, rs.get(2).attempts(), "two attempts");
      eq(1, sleeps.size(), "one backoff");
      eq(f.made, f.deleted, "every child deleted: made " + f.made + ", deleted " + f.deleted);
      Map<?, ?> forkBody = (Map<?, ?>) seen(stub, "POST", "/v1/sandboxes/sbx_base/fork").get(0).body();
      eq("run-1", forkBody.get("run_id"), "children labelled with the run");
      eq(600L, forkBody.get("lease_s"), "children leased");
      eq(2L, forkBody.get("n"), "preforked concurrency");
    }
  }

  public void testForkMapTimeoutDeletesTheChild() throws Exception {
    Forker f = new Forker();
    try (Stub stub = new Stub(s -> f.answer(s))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox base = new CloudSandbox(cloud, box("sbx_base"));
      List<ForkResult<String, String>> rs = cloud.forkMap(base, List.of("slow"), (child, x) -> {
        Thread.sleep(5000);
        return x;
      }, new CloudOptions.ForkMap().timeout(Duration.ofMillis(100)));
      eq("timeout", rs.get(0).failureClass(), "timed out");
      check(rs.get(0).ms() < 4000, "did not wait for fn: " + rs.get(0).ms());
      eq(f.made, f.deleted, "its child deleted");
      check(cloud.forkMap(base, List.<String>of(), (c, x) -> x).isEmpty(), "no items");
    }
  }

  public void testForkMapThrowsOnlyOnErrorsAndStillCleansUp() throws Exception {
    Forker f = new Forker();
    try (Stub stub = new Stub(s -> f.answer(s))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox base = new CloudSandbox(cloud, box("sbx_base"));
      raises(AssertionError.class, () -> cloud.forkMap(base, List.of(1, 2, 3), (child, x) -> {
        throw new AssertionError("an Error escapes");
      }, new CloudOptions.ForkMap().concurrency(3)));
      check(!f.made.isEmpty(), "made some");
      eq(f.made, f.deleted, "all deleted after an Error");
    }
  }

  // --- RL runs -------------------------------------------------------------------------

  public void testRunLifecycleBatchesAndExport() throws Exception {
    List<Integer> chunks = java.util.Collections.synchronizedList(new ArrayList<>());
    try (Stub stub = new Stub(s -> {
      String r = s.method() + " " + s.path();
      if (r.equals("POST /v1/rl/runs")) return new Answer(201, m("run", m("id", "run_1", "name", "grpo", "status", "running")));
      if (r.equals("POST /v1/rl/runs/run_1/rollouts")) {
        int n = ((List<?>) ((Map<?, ?>) s.body()).get("rollouts")).size();
        chunks.add(n);
        return new Answer(200, m("recorded", n, "ids", List.of()));
      }
      if (r.equals("GET /v1/rl/runs/run_1")) return new Answer(200, m("run", m("id", "run_1", "status", "running"), "summary", m("rollouts", 1200), "recommendations", List.of()));
      if (r.startsWith("GET /v1/rl/runs/run_1/rollouts")) return new Answer(200, m("rollouts", List.of(m("id", "ro_1")), "next_cursor", null));
      if (r.equals("GET /v1/rl/runs/run_1/export?format=jsonl")) return new Answer(200, "{\"id\":\"ro_1\"}\n".getBytes(StandardCharsets.UTF_8));
      if (r.startsWith("GET /v1/rl/runs?")) return new Answer(200, m("runs", List.of(m("id", "run_1")), "next_cursor", null));
      if (r.equals("PATCH /v1/rl/runs/run_1")) return new Answer(200, m("run", m("id", "run_1", "status", ((Map<?, ?>) s.body()).get("status"))));
      if (r.equals("POST /v1/sandboxes/drain")) return new Answer(200, m("deleted", 2, "ids", List.of("a", "b"), "failed", List.of(), "more", false));
      return new Answer(404, m("error", "not_found"));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      Run run = Run.start(cloud, "grpo", new Run.Options().project("p").config(Map.of("lr", 1e-5)).flushEvery(500));
      eq("run_1", run.id(), "id");
      eq(m("name", "grpo", "project", "p", "config", Map.of("lr", 1e-5)), stub.seen.get(0).body(), "create body");
      eq(Map.of("kosa8.run", "run_1"), run.label(), "label");
      List<Map<String, Object>> rows = new ArrayList<>();
      for (int i = 0; i < 1200; i++) rows.add(m("step", 0, "index", i, "reward", 1.0));
      run.record(rows);
      eq(List.of(500, 500, 200), chunks, "sent 500 at a time, all of a full buffer");
      eq(0, run.pending(), "nothing pending");
      run.record(m("step", 1, "reward", 0.0));
      eq(1, run.pending(), "buffered below flushEvery");
      eq(1200, ((Number) ((Map<?, ?>) run.summary().get("summary")).get("rollouts")).intValue(), "summary");
      eq("ro_1", ((Map<?, ?>) ((List<?>) run.rollouts(new Run.RolloutQuery().step(0).failureClass("infra").limit(10)).get("rollouts")).get(0)).get("id"), "rollouts page");
      check(seen(stub, "GET", "/v1/rl/runs/run_1/rollouts?step=0&failure_class=infra&limit=10").size() == 1, "query");
      Path tmp = Files.createTempFile("kosa8-export", ".jsonl");
      try {
        run.export(tmp);
        eq("{\"id\":\"ro_1\"}\n", Files.readString(tmp), "export");
      } finally {
        Files.deleteIfExists(tmp);
      }
      eq("run_1", Run.list(cloud, "p", "running", null, 5).get(0).get("id"), "list");
      check(seen(stub, "GET", "/v1/rl/runs?project=p&status=running&limit=5").size() == 1, "list query");
      run.close();
      eq(List.of(500, 500, 200, 1), chunks, "finish flushed the rest");
      eq(1, seen(stub, "POST", "/v1/sandboxes/drain").size(), "drained");
      eq(m("run_id", "run_1"), seen(stub, "POST", "/v1/sandboxes/drain").get(0).body(), "drain by run");
      eq(m("status", "finished"), seen(stub, "PATCH", "/v1/rl/runs/run_1").get(0).body(), "marked finished");
      eq("finished", run.status(), "status");
      run.close();
      eq(1, seen(stub, "PATCH", "/v1/rl/runs/run_1").size(), "twice is once");
      raises(IllegalStateException.class, () -> run.map(new CloudSandbox(cloud, box("sbx_b")), List.of(1), (b, t) -> 1.0, null));
      raises(IllegalArgumentException.class, () -> Run.start(cloud, null, null));
      raises(IllegalArgumentException.class, () -> new Run.Options().flushEvery(501));
    }
  }

  public void testRunWithMarksFailedAndKeepsRowsOnError() throws Exception {
    AtomicInteger fail = new AtomicInteger(1);
    try (Stub stub = new Stub(s -> {
      String r = s.method() + " " + s.path();
      if (r.equals("POST /v1/rl/runs/run_9/rollouts")) {
        if (fail.getAndDecrement() > 0) return new Answer(500, m("error", "internal"));
        return new Answer(200, m("recorded", ((List<?>) ((Map<?, ?>) s.body()).get("rollouts")).size()));
      }
      if (r.equals("PATCH /v1/rl/runs/run_9")) return new Answer(200, m("run", m("id", "run_9")));
      if (r.equals("POST /v1/sandboxes/drain")) return new Answer(200, m("deleted", 0, "failed", List.of(), "more", false));
      return new Answer(404, m("error", "not_found"));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      raises(IllegalStateException.class, () -> {
        try {
          Run.with(cloud, null, new Run.Options().runId("run_9").drainOnExit(false), run -> {
            run.record(m("reward", 1.0));
            raises(Kosa8Exception.class, run::flush);
            eq(1, run.pending(), "unsent rows stay buffered");
            throw new IllegalStateException("trainer crashed");
          });
        } catch (RuntimeException e) {
          throw e;
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      eq(m("status", "failed"), seen(stub, "PATCH", "/v1/rl/runs/run_9").get(0).body(), "marked failed");
      eq(2, seen(stub, "POST", "/v1/rl/runs/run_9/rollouts").size(), "retried at finish");
      eq(0, seen(stub, "POST", "/v1/sandboxes/drain").size(), "no drain with drainOnExit false");
    }
  }

  public void testRunHeartbeatThread() throws Exception {
    try (Stub stub = new Stub(s -> {
      if (s.path().equals("/v1/sandboxes/heartbeat")) return new Answer(200, m("extended", 3));
      if (s.path().startsWith("/v1/rl/runs")) return new Answer(200, m("run", m("id", "run_h")));
      return new Answer(200, m("deleted", 0, "failed", List.of(), "more", false));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      Run run = Run.attach(cloud, "run_h", new Run.Options().leaseS(60).heartbeat(Duration.ofMillis(20)));
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (seen(stub, "POST", "/v1/sandboxes/heartbeat").isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
      run.close();
      check(!seen(stub, "POST", "/v1/sandboxes/heartbeat").isEmpty(), "heartbeats sent");
      eq(m("run_id", "run_h"), seen(stub, "POST", "/v1/sandboxes/heartbeat").get(0).body(), "heartbeat selects the run");
      int n = seen(stub, "POST", "/v1/sandboxes/heartbeat").size();
      Thread.sleep(80);
      eq(n, seen(stub, "POST", "/v1/sandboxes/heartbeat").size(), "stopped at finish");
    }
  }

  public void testRunMapWithSealedGrading() throws Exception {
    Forker f = new Forker();
    List<String> gradeKeys = java.util.Collections.synchronizedList(new ArrayList<>());
    AtomicInteger grades = new AtomicInteger();
    java.util.concurrent.atomic.AtomicBoolean failedOnce = new java.util.concurrent.atomic.AtomicBoolean();
    List<Object> recorded = java.util.Collections.synchronizedList(new ArrayList<>());
    try (Stub stub = new Stub(s -> {
      Answer a = f.answer(s);
      if (a != null) return a;
      String r = s.method() + " " + s.path();
      if (r.equals("POST /v1/rl/runs")) return new Answer(201, m("run", m("id", "run_m")));
      if (r.endsWith("/grade")) {
        gradeKeys.add(s.headers().get("idempotency-key"));
        Map<?, ?> rollout = (Map<?, ?>) ((Map<?, ?>) s.body()).get("rollout");
        String group = String.valueOf(rollout.get("group"));
        long index = ((Number) rollout.get("index")).longValue();
        int g = grades.incrementAndGet();
        check(((Map<?, ?>) s.body()).get("files") instanceof Map<?, ?>, "the task's hidden files went to the grader");
        check(g > 0, "counted");
        if (group.equals("0") && index == 0 && failedOnce.compareAndSet(false, true)) {
          // The first grade's host failed: 200, no reward, retryable.
          return new Answer(200, m("reward", null, "exit", null, "failure_class", "infra", "retryable", true, "grader_id", "sbx_g", "error", "host_failed"));
        }
        if (group.equals("1") && index == 1) {
          return new Answer(200, m("reward", null, "exit", null, "failure_class", "timeout", "retryable", false, "grader_id", "sbx_g", "recorded", true));
        }
        double reward = group.equals("0") ? 1.0 : 0.0;
        return new Answer(200, m("reward", reward, "exit", reward > 0 ? 0 : 1, "failure_class", reward > 0 ? "ok" : "exit_nonzero",
            "retryable", false, "grader_id", "sbx_g", "recorded", !group.equals("0")));
      }
      if (r.equals("POST /v1/rl/runs/run_m/rollouts")) {
        recorded.addAll((List<?>) ((Map<?, ?>) s.body()).get("rollouts"));
        return new Answer(200, m("recorded", ((List<?>) ((Map<?, ?>) s.body()).get("rollouts")).size()));
      }
      if (r.equals("PATCH /v1/rl/runs/run_m")) return new Answer(200, m("run", m("id", "run_m")));
      return new Answer(404, m("error", "not_found"));
    })) {
      List<Duration> sleeps = new ArrayList<>();
      Kosa8Cloud cloud = stub.cloud(sleeps);
      CloudSandbox base = new CloudSandbox(cloud, box("sbx_base"));
      List<Map<String, Object>> tasks = List.of(m("id", "t0", "files", Map.of("/grader/t.py", "ok")), m("id", "t1", "files", Map.of("/grader/t.py", "no")));
      Rollouts rs;
      try (Run run = Run.start(cloud, "m", new Run.Options().leaseS(300))) {
        rs = run.map(base, tasks, (box, task) -> m("tokens", 12), new Run.MapOptions().gradeCmd(List.of("python", "/grader/t.py"))
            .groupSize(2).concurrency(2).mask(Set.of("infra", "quota", "error", "timeout")));
        eq(2, run.pending() + run.recorded(), "only the rows the grader did not record are recorded by the SDK");
      }
      eq(4, rs.size(), "2 tasks x 2");
      eq(java.util.Arrays.asList(1.0, 1.0, 0.0, null), rs.rewards(), "rewards in order, timeout masked");
      eq(List.of(true, true, true, false), rs.mask(), "mask");
      eq(2, rs.groups().size(), "groups");
      eq(2.0 / 3, rs.meanReward(), "mean of the unmasked");
      eq(Map.of("ok", 2, "exit_nonzero", 1, "timeout", 1), rs.byClass(), "by class");
      eq("t0", rs.get(0).meta.get("task"), "task id in meta");
      eq(12L, rs.get(0).tokens, "tokens from solve");
      eq(5, gradeKeys.size(), "the failed grade was retried alone");
      eq(5, new java.util.HashSet<>(gradeKeys).size(), "each grade attempt has a new key");
      check(!sleeps.isEmpty(), "backed off before regrading");
      eq(2, recorded.size(), "SDK recorded two rows: " + recorded);
      for (Object row : recorded) eq("0", ((Map<?, ?>) row).get("group"), "the group-0 rows");
      eq(f.made, f.deleted, "every rollout sandbox deleted");
      Map<?, ?> forkBody = (Map<?, ?>) seen(stub, "POST", "/v1/sandboxes/sbx_base/fork").get(0).body();
      eq("run_m", forkBody.get("run_id"), "forks labelled with the run");
      eq(300L, forkBody.get("lease_s"), "forks leased");
    }
  }

  public void testRunMapRewardFromSolveAndErrors() throws Exception {
    Forker f = new Forker();
    List<Object> recorded = java.util.Collections.synchronizedList(new ArrayList<>());
    try (Stub stub = new Stub(s -> {
      Answer a = f.answer(s);
      if (a != null) return a;
      if (s.path().equals("/v1/rl/runs/run_r/rollouts")) {
        recorded.addAll((List<?>) ((Map<?, ?>) s.body()).get("rollouts"));
        return new Answer(200, m("recorded", 1));
      }
      return new Answer(200, m("run", m("id", "run_r")));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox base = new CloudSandbox(cloud, box("sbx_base"));
      Rollouts rs;
      try (Run run = Run.attach(cloud, "run_r", new Run.Options().drainOnExit(false))) {
        rs = run.map(base, List.of(0, 1, 2, 3), (box, t) -> {
          if (t == 1) throw new IllegalStateException("solve bug");
          if (t == 2) return "no reward";
          if (t == 3) return m("reward", 0.25, "metrics", m("len", 3));
          return 1;
        }, new Run.MapOptions().step(7));
      }
      eq(1.0, rs.get(0).reward, "a number");
      check(rs.get(1).masked && rs.get(1).reward == null, "your error is masked");
      eq("error", rs.get(1).failureClass, "error class");
      eq("error", rs.get(2).failureClass, "no reward: a harness error");
      eq(0.25, rs.get(3).reward, "a map");
      eq(Map.of("len", 3), rs.get(3).metrics, "metrics");
      eq(4, recorded.size(), "every rollout recorded");
      Map<?, ?> errRow = (Map<?, ?>) recorded.stream().filter(x -> ((Map<?, ?>) x).get("index") != null && "1".equals(((Map<?, ?>) x).get("group"))).findFirst().get();
      eq("client", errRow.get("failure_class"), "error recorded as client");
      eq("error", ((Map<?, ?>) errRow.get("meta")).get("sdk_failure_class"), "with the SDK's class in meta");
      eq(7L, errRow.get("step"), "step");
      eq(f.made, f.deleted, "deleted");
    }
  }

  // --- org templates and tokens --------------------------------------------------------

  public void testOrgTemplates() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    try (Stub stub = new Stub(s -> {
      String r = s.method() + " " + s.path();
      return switch (r) {
        case "POST /v1/templates" -> new Answer(202, m("template", m("id", "tpl_12345678", "name", "swe-1", "state", "queued")));
        case "POST /v1/templates/import" -> new Answer(202, m("import_id", "imp_1", "requested", 2, "queued", 2, "templates", List.of(), "failed", List.of()));
        case "GET /v1/templates" -> new Answer(200, m("templates", List.of(m("key", "base")), "org_templates", List.of(m("id", "tpl_12345678"))));
        case "GET /v1/templates/swe-1" -> new Answer(200, m("template", m("id", "tpl_12345678", "state", polls.incrementAndGet() < 3 ? "building" : "ready")));
        case "GET /v1/templates/bad" -> new Answer(200, m("template", m("id", "tpl_x", "state", "failed", "last_error", "pull failed")));
        case "DELETE /v1/templates/swe-1" -> new Answer(200, m("ok", true, "id", "tpl_12345678"));
        default -> new Answer(404, m("error", "not_found"));
      };
    })) {
      List<Duration> sleeps = new ArrayList<>();
      Kosa8Cloud cloud = stub.cloud(sleeps);
      Map<String, Object> t = cloud.orgTemplates().build("swe-1", new CloudOptions.TemplateBuild().image("python:3.12-slim@sha256:" + "a".repeat(64))
          .setupCmd(List.of("pip", "install", "pytest")).size("4x8").region("eu-hel1"));
      eq("queued", t.get("state"), "queued");
      eq(m("name", "swe-1", "image", "python:3.12-slim@sha256:" + "a".repeat(64), "size", "4x8", "setup_cmd", List.of("pip", "install", "pytest"), "region", "eu-hel1"),
          stub.seen.get(0).body(), "build body");
      cloud.orgTemplates().build("df", new CloudOptions.TemplateBuild().dockerfile("FROM debian:13\n").buildArgs(Map.of("V", "1")).cpus(2, 4096).setupCmd("make"));
      eq(m("name", "df", "dockerfile", "FROM debian:13\n", "build_args", Map.of("V", "1"), "cpus", 2L, "mem_mb", 4096L, "setup_cmd", "make"),
          stub.seen.get(1).body(), "dockerfile body");
      Map<String, Object> imp = cloud.orgTemplates().importImages(List.of(Map.of("name", "a", "image", "x:1"), Map.of("name", "b", "image", "y:1")),
          new CloudOptions.TemplateImport().partialOk(true));
      eq(2L, imp.get("queued"), "import");
      eq(true, ((Map<?, ?>) stub.seen.get(2).body()).get("partial_ok"), "partial_ok");
      eq("tpl_12345678", cloud.orgTemplates().list().get(0).get("id"), "list org templates");
      eq("base", cloud.templates().get(0).get("key"), "templates() unchanged");
      eq("ready", cloud.orgTemplates().waitReady("swe-1", Duration.ofMinutes(1), Duration.ofSeconds(2)).get("state"), "waited");
      eq(2, sleeps.size(), "polled with the sleeper");
      var e = raises(Kosa8Exception.class, () -> cloud.orgTemplates().waitReady("bad", Duration.ofMinutes(1), Duration.ofSeconds(1)));
      check(e.getMessage().contains("pull failed"), e.getMessage());
      cloud.orgTemplates().delete("swe-1");
      eq("DELETE /v1/templates/swe-1", seen(stub, "DELETE", "/v1/templates").get(0).method() + " " + seen(stub, "DELETE", "/v1/templates").get(0).path(), "delete");
      raises(IllegalArgumentException.class, () -> cloud.orgTemplates().build("Bad Name", new CloudOptions.TemplateBuild().image("x")));
      raises(IllegalArgumentException.class, () -> cloud.orgTemplates().build("x", new CloudOptions.TemplateBuild()));
      raises(IllegalArgumentException.class, () -> cloud.orgTemplates().build("x", new CloudOptions.TemplateBuild().image("x").buildArgs(Map.of("A", "1"))));
      raises(IllegalArgumentException.class, () -> cloud.orgTemplates().importImages(List.of(), null));
    }
  }

  public void testCapabilityTokens() throws Exception {
    try (Stub stub = new Stub(s -> {
      String r = s.method() + " " + s.path();
      return switch (r) {
        case "POST /v1/tokens" -> new Answer(201, m("token", m("id", "tok_1", "secret", "k8c_tok_abc", "state", "active")));
        case "GET /v1/tokens?all=1" -> new Answer(200, m("tokens", List.of(m("id", "tok_1", "state", "revoked"))));
        case "GET /v1/tokens" -> new Answer(200, m("tokens", List.of(m("id", "tok_1"))));
        case "DELETE /v1/tokens/tok_1" -> new Answer(200, m("token", m("id", "tok_1", "state", "revoked")));
        default -> new Answer(200, m("org", m("id", "o")));
      };
    })) {
      Kosa8Cloud cloud = stub.cloud();
      Map<String, Object> t = cloud.tokens().create(List.of("sandboxes:read", "sandboxes:write"),
          new CloudOptions.Token().sandboxIds(List.of("sbx_1")).labels(Map.of("user", "u1")).ttlS(600).name("student"));
      eq("k8c_tok_abc", t.get("secret"), "secret once");
      eq(m("scopes", List.of("sandboxes:read", "sandboxes:write"), "sandbox_ids", List.of("sbx_1"), "labels", Map.of("user", "u1"), "ttl_s", 600L, "name", "student"),
          stub.seen.get(0).body(), "token body");
      eq("tok_1", cloud.tokens().list().get(0).get("id"), "list");
      eq("revoked", cloud.tokens().list(true).get(0).get("state"), "list all");
      eq("revoked", cloud.tokens().revoke("tok_1").get("state"), "revoke");
      Kosa8Cloud asToken = Kosa8Cloud.builder().apiKey("k8c_tok_abc").baseUrl(stub.url()).getenv(x -> null).build();
      asToken.me();
      eq("Bearer k8c_tok_abc", stub.seen.get(stub.seen.size() - 1).headers().get("authorization"), "a token is a key");
      raises(IllegalArgumentException.class, () -> cloud.tokens().create(List.of("admin"), null));
      raises(IllegalArgumentException.class, () -> cloud.tokens().create(List.of(), null));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Token().ttlS(86_401));
      raises(IllegalArgumentException.class, () -> cloud.tokens().revoke(""));
    }
  }
}
