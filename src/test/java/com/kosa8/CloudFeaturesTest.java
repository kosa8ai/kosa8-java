package com.kosa8;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * kosa8 Cloud features against a stub control plane: sized/Docker creates, env, background exec, snapshots,
 * previews, terminal, raw files and links, egress, secrets, schedules, code interpreter, metrics, logs,
 * labels and Pipelines.
 */
public final class CloudFeaturesTest extends ClientTest {
  static CloudTest.Answer ok(Object body) { return new CloudTest.Answer(200, body); }

  static Map<String, Object> m(Object... kv) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
    return out;
  }

  static CloudSandbox box(CloudTest.Stub stub) { return new CloudSandbox(stub.cloud(), m("id", "sbx_1", "state", "running")); }

  static CloudTest.Seen last(CloudTest.Stub stub) {
    synchronized (stub.seen) { return stub.seen.get(stub.seen.size() - 1); }
  }

  public void testUserAgentIsExplicit() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> ok(m()))) {
      stub.cloud().me();
      // api.kosa8.com's edge refuses some default library user agents.
      eq("kosa8-java/" + Kosa8Client.VERSION, last(stub).headers().get("user-agent"), "user agent");
    }
  }

  public void testSizedCreateSendsEverythingAndWaits() throws Exception {
    AtomicInteger gets = new AtomicInteger();
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> s.method().equals("POST")
        ? new CloudTest.Answer(202, m("sandbox", m("id", "sbx_9", "state", "creating")))
        : ok(m("sandbox", m("id", "sbx_9", "state", gets.incrementAndGet() > 1 ? "running" : "creating", "persistent", true,
            "name", "web", "labels", m("team", "a")))))) {
      CloudSandbox b = stub.cloud().sandboxes().create(new CloudOptions.Create().size("4x8").image("kosa8-template/universal-24.04-20261115.2")
          .diskGb(64).docker(true).persistent(true).cpu("dedicated").region("us").name("web").labels(Map.of("team", "a"))
          .env(Map.of("GREETING", "hi")).secrets(List.of("openai")).egress(false, List.of("pypi.org"), null, null).ttlSeconds(3600));
      eq("running", b.state(), "waited for running");
      eq(2, gets.get(), "polled");
      check(b.persistent(), "persistent");
      eq("web", b.name(), "name");
      eq(Map.of("team", "a"), b.labels(), "labels");
      eq(m("ttl_s", 3600L, "size", "4x8", "image", "kosa8-template/universal-24.04-20261115.2", "disk_gb", 64L, "persistent", true,
          "docker", true, "cpu", "dedicated", "region", "us", "name", "web", "labels", m("team", "a"), "env", m("GREETING", "hi"),
          "secrets", List.of("openai"), "egress", m("internet", false, "allow", List.of("pypi.org"), "deny", List.of(), "presets", List.of())),
          stub.seen.get(0).body(), "create body");
    }
  }

  public void testCreateNoWaitAndFailedBoot() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> s.method().equals("POST")
        ? new CloudTest.Answer(202, m("sandbox", m("id", "sbx_9", "state", "creating")))
        : ok(m("sandbox", m("id", "sbx_9", "state", "error", "last_error", "no image"))))) {
      CloudSandbox b = stub.cloud().sandboxes().create(new CloudOptions.Create().cpus(2, 4096).noWait());
      eq("creating", b.state(), "no wait");
      var e = raises(Kosa8Exception.class, () -> b.waitRunning(Duration.ofSeconds(5), Duration.ZERO));
      check(e.getMessage().contains("no image"), e.getMessage());
    }
  }

  public void testCreateValidation() {
    List<CloudOptions.Create> bad = List.of(new CloudOptions.Create().size("3x5"), new CloudOptions.Create().size("2x4").cpus(2, 4096),
        new CloudOptions.Create().diskGb(4), new CloudOptions.Create().cpu("fast"), new CloudOptions.Create().name("-x"),
        new CloudOptions.Create().env(Map.of("1X", "y")), new CloudOptions.Create().env(Map.of("X", "a".repeat(8193))),
        new CloudOptions.Create().labels(Map.of("bad key", "v")), new CloudOptions.Create().snapshot("sbx_1"),
        new CloudOptions.Create().snapshot("snap_abcdefgh").size("2x4"), new CloudOptions.Create().secrets(List.of("Bad_Name")));
    for (CloudOptions.Create o : bad) raises(IllegalArgumentException.class, o::body);
  }

  public void testCreateFromSnapshotAndListFilters() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> s.method().equals("POST")
        ? new CloudTest.Answer(201, m("sandbox", m("id", "sbx_3", "state", "running", "snapshot_id", "snap_abcdefgh")))
        : ok(m("sandboxes", List.of())))) {
      Kosa8Cloud c = stub.cloud();
      eq("snap_abcdefgh", c.sandboxes().create(new CloudOptions.Create().snapshot("snap_abcdefgh").env(Map.of("A", "1")).name("copy")).snapshotId(), "snapshot id");
      eq(m("name", "copy", "env", m("A", "1"), "snapshot", "snap_abcdefgh"), last(stub).body(), "snapshot body");
      Map<String, String> labels = new LinkedHashMap<>();
      labels.put("team", "a");
      labels.put("ci", "");
      c.sandboxes().list(new CloudOptions.List().name("web").labels(labels));
      eq("/v1/sandboxes?name=web&label=ci&label=team%3Da", last(stub).path(), "list filters");
    }
  }

  public void testBackgroundExec() throws Exception {
    AtomicInteger polls = new AtomicInteger();
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> s.method().equals("POST")
        ? new CloudTest.Answer(202, m("exec", m("id", "bg_0123456789abcdef", "state", "running", "pid", 7)))
        : ok(m("exec", polls.incrementAndGet() > 1 ? m("id", "bg_0123456789abcdef", "state", "exited", "exit", 0, "output", "ab")
            : m("id", "bg_0123456789abcdef", "state", "running"))))) {
      CloudSandbox b = box(stub);
      Map<String, String> env = new LinkedHashMap<>();
      env.put("B", "2");
      env.put("A", "1");
      CloudBackgroundExec e = b.execBackground(List.of("sleep", "60"), null, env);
      eq("bg_0123456789abcdef", e.id(), "id");
      eq(7, e.pid(), "pid");
      check(!e.done(), "running");
      eq(m("cmd", List.of("sleep", "60"), "background", true, "env", List.of("A=1", "B=2")), last(stub).body(), "bg body");
      CloudBackgroundExec done = b.waitBackground(e.id(), Duration.ofSeconds(5), Duration.ZERO);
      eq(0, done.exitCode(), "exit");
      eq("ab", done.output(), "output");
      eq("/v1/sandboxes/sbx_1/exec/bg_0123456789abcdef", last(stub).path(), "poll path");
      raises(IllegalArgumentException.class, () -> b.execBackground(List.of(), null, null));
    }
  }

  public void testSnapshots() throws Exception {
    Map<String, Object> snap = m("id", "snap_abcdefgh", "name", "warm", "state", "ready", "bytes", 1024);
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> switch (s.method()) {
      case "POST" -> new CloudTest.Answer(201, m("snapshot", snap));
      case "DELETE" -> ok(m("ok", true));
      default -> s.path().startsWith("/v1/snapshots/") ? ok(m("snapshot", snap)) : ok(m("snapshots", List.of(snap)));
    })) {
      CloudSnapshot sn = box(stub).snapshot("warm");
      eq("snap_abcdefgh", sn.id(), "id");
      eq(1024L, sn.bytes(), "bytes");
      eq("/v1/sandboxes/sbx_1/snapshots", last(stub).path(), "snapshot path");
      eq(m("name", "warm"), last(stub).body(), "snapshot body");
      Kosa8Cloud c = stub.cloud();
      eq(1, c.snapshots().list("sbx_1").size(), "list");
      eq("/v1/snapshots?sandbox_id=sbx_1", last(stub).path(), "list path");
      eq("ready", c.snapshots().get("snap_abcdefgh").state(), "get");
      c.snapshots().delete("snap_abcdefgh");
      eq("DELETE", last(stub).method(), "delete");
    }
  }

  public void testPreviews() throws Exception {
    Map<String, Object> pv = m("port", 3000, "url", "https://p.kosa8labs.com/?t=x", "host", "p.kosa8labs.com", "token", "x");
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> switch (s.method()) {
      case "GET" -> ok(m("previews", List.of(pv)));
      case "DELETE" -> ok(m("deleted", true));
      default -> ok(m("preview", pv));
    })) {
      CloudPreviews p = box(stub).previews();
      CloudPreview got = p.create(3000, false, 600, true);
      check(got.url().contains("kosa8labs"), got.url());
      check(!got.toString().contains("t=x"), "toString hides the token");
      eq(m("port", 3000L, "expires_in_s", 600L, "wake", true), last(stub).body(), "create body");
      eq(1, p.list().size(), "list");
      p.link(3000, 60);
      eq("/v1/sandboxes/sbx_1/previews/3000/link", last(stub).path(), "link");
      p.rotate(3000);
      eq("/v1/sandboxes/sbx_1/previews/3000/rotate", last(stub).path(), "rotate");
      p.delete(3000);
      eq("/v1/sandboxes/sbx_1/previews/3000", last(stub).path(), "delete");
      raises(IllegalArgumentException.class, () -> p.create(0));
    }
  }

  public void testTerminalRawFilesAndLink() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().endsWith("/terminal")) return ok(m("terminal", m("url", "wss://h/t")));
      if (s.path().endsWith("/files/link")) return ok(m("link", m("url", "https://h/f", "method", "PUT")));
      if (s.method().equals("GET")) return ok("big file".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return ok(m("file", m("size", s.raw().length, "mode", 420)));
    })) {
      CloudSandbox b = box(stub);
      eq("wss://h/t", b.terminal(80, 24, List.of("bash")).get("url"), "terminal");
      eq(m("cols", 80L, "rows", 24L, "command", List.of("bash")), last(stub).body(), "terminal body");
      eq("PUT", b.files().link("/workspace/a", "put", 120).get("method"), "link");
      eq(m("path", "/workspace/a", "method", "PUT", "ttl_s", 120L), last(stub).body(), "link body");
      raises(IllegalArgumentException.class, () -> b.files().link("/a", "POST", null));
      eq("big file", new String(b.files().download("/workspace/a"), java.nio.charset.StandardCharsets.UTF_8), "download");
      eq("/v1/sandboxes/sbx_1/files/raw?path=%2Fworkspace%2Fa", last(stub).path(), "raw path");
      CloudFileInfo fi = b.files().upload("/workspace/a", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      eq(5L, fi.size(), "upload size");
      eq(420, fi.mode(), "mode is a number");
    }
  }

  public void testEgress() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> s.path().endsWith("/presets") ? ok(m("presets", m("pypi", List.of("pypi.org"))))
        : ok(m("egress", m("source", "sandbox"))))) {
      CloudSandbox b = box(stub);
      eq("sandbox", b.setEgress(false, List.of("github.com"), null, List.of("pypi")).get("source"), "set");
      eq(m("internet", false, "allow", List.of("github.com"), "deny", List.of(), "presets", List.of("pypi")), last(stub).body(), "egress body");
      b.egress();
      eq("GET", last(stub).method(), "get");
      b.resetEgress();
      eq("DELETE", last(stub).method(), "reset");
      eq(List.of("pypi.org"), stub.cloud().egressPresets().get("pypi"), "presets");
    }
  }

  public void testSecrets() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> switch (s.method()) {
      case "PUT" -> new CloudTest.Answer(201, m("secret", m("name", "npm-token", "pipelines", true)));
      case "DELETE" -> ok(m("still_held_by", List.of("sbx_1")));
      default -> s.path().contains("sandboxes") ? ok(m("secrets", List.of("npm-token"))) : ok(m("secrets", List.of(m("name", "npm-token"))));
    })) {
      Kosa8Cloud c = stub.cloud();
      eq(true, c.secrets().set("npm-token", "s3cr3t", "https://registry.npmjs.org", true).get("pipelines"), "set");
      eq(m("value", "s3cr3t", "upstream", "https://registry.npmjs.org", "pipelines", true), last(stub).body(), "secret body");
      eq(1, c.secrets().list().size(), "list");
      eq(List.of("sbx_1"), c.secrets().delete("npm-token"), "still held");
      eq(List.of("npm-token"), box(stub).secrets(), "sandbox secrets");
      raises(IllegalArgumentException.class, () -> c.secrets().set("Bad Name", "x", null, false));
      raises(IllegalArgumentException.class, () -> c.secrets().set("ok", "", null, false));
    }
  }

  public void testSchedules() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.method().equals("DELETE")) return ok(m("deleted", true));
      if (s.path().endsWith("/run")) return ok(m("run", m("status", "ok", "output", "hi")));
      if (s.method().equals("POST")) return new CloudTest.Answer(201, m("schedule", m("id", "sch_1")));
      return ok(m("schedules", List.of(m("id", "sch_1"))));
    })) {
      CloudSchedules s = box(stub).schedules();
      eq("sch_1", s.create("0 * * * *", List.of("echo", "hi"), Map.of("A", "1"), "hourly", true).get("id"), "create");
      eq(m("cron", "0 * * * *", "cmd", List.of("echo", "hi"), "env", List.of("A=1"), "name", "hourly", "wake", true), last(stub).body(), "schedule body");
      eq(1, s.list().size(), "list");
      eq("hi", s.run("sch_1").get("output"), "run");
      s.delete("sch_1");
      raises(IllegalArgumentException.class, () -> s.create("* * * * *", List.of()));
    }
  }

  public void testCodeInterpreter() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().endsWith("/code")) return ok(m("stdout", "42\n", "results", List.of(m("type", "image/png"))));
      if (s.path().endsWith("/reset")) return ok(m("reset", "a"));
      if (s.method().equals("DELETE")) return ok(m("deleted", "a"));
      if (s.method().equals("POST")) return new CloudTest.Answer(201, m("context", m("name", "a")));
      return ok(m("contexts", List.of(m("name", "a"))));
    })) {
      CloudSandbox b = box(stub);
      eq("42\n", b.runCode("print(42)", "a", 10.0).get("stdout"), "run code");
      eq(m("code", "print(42)", "language", "python", "context", "a", "timeout_s", 10L), last(stub).body(), "code body");
      eq("a", b.createCodeContext("a").get("name"), "create context");
      eq(1, b.codeContexts().size(), "contexts");
      b.resetCodeContext("a");
      eq("/v1/sandboxes/sbx_1/code/contexts/a/reset", last(stub).path(), "reset");
      b.deleteCodeContext("a");
      eq("DELETE", last(stub).method(), "delete");
      raises(IllegalArgumentException.class, () -> b.runCode(""));
    }
  }

  public void testMetricsLogsLabelsCapabilities() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().endsWith("/metrics")) return ok(m("metrics", m("cpu_pct", 12.5)));
      if (s.path().contains("/logs")) return ok(m("entries", List.of(m("stdout_tail", "a"))));
      if (s.path().endsWith("/capabilities")) return ok(m("background_exec", true));
      Object labels = s.body() instanceof Map<?, ?> bm ? bm.get("labels") : null;
      return ok(m("labels", labels == null ? m() : labels));
    })) {
      CloudSandbox b = box(stub);
      eq(12.5, b.metrics().get("cpu_pct"), "metrics");
      eq("a", b.logs(5).get(0).get("stdout_tail"), "logs");
      eq("/v1/sandboxes/sbx_1/logs?limit=5", last(stub).path(), "logs path");
      eq(Map.of("env", "prod"), b.setLabels(Map.of("env", "prod")), "labels");
      eq(Map.of("env", "prod"), b.labels(), "info labels");
      b.setLabels(null);
      check(new String(last(stub).raw(), java.nio.charset.StandardCharsets.UTF_8).contains("\"labels\":null"), "clear sends null");
      eq(true, stub.cloud().capabilities().get("background_exec"), "capabilities");
    }
  }

  public void testPipelines() throws Exception {
    AtomicInteger gets = new AtomicInteger();
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      String p = s.path();
      if (p.endsWith("/cancel")) return ok(m("run", m("state", "cancelled")));
      if (p.contains("/logs")) return ok(m("done", true, "logs", List.of(m("job", "test", "next", 4))));
      if (p.contains("/artifacts/")) return ok("tgz".getBytes(java.nio.charset.StandardCharsets.UTF_8));
      if (p.endsWith("/artifacts")) return ok(m("artifacts", List.of(m("path", "dist"))));
      if (p.startsWith("/v1/pipelines/hooks") && s.method().equals("POST")) return new CloudTest.Answer(201, m("hook", m("id", "hk_1", "secret", "whsec")));
      if (p.startsWith("/v1/pipelines/hooks") && s.method().equals("DELETE")) return ok(m("deleted", "hk_1"));
      if (p.startsWith("/v1/pipelines/hooks")) return ok(m("hooks", List.of(m("id", "hk_1"))));
      if (s.method().equals("POST")) return new CloudTest.Answer(201, m("run", m("id", "run_1", "state", "queued")));
      if (p.equals("/v1/pipelines/runs/run_1")) return ok(m("run", m("id", "run_1", "state", gets.incrementAndGet() > 1 ? "success" : "running")));
      return ok(m("runs", List.of(m("id", "run_1"))));
    })) {
      Kosa8Cloud.Pipelines pl = stub.cloud().pipelines();
      eq("run_1", pl.run("https://github.com/me/app", m("ref", "main", "size", "4x8", "secrets", List.of("npm-token"))).get("id"), "run");
      eq(m("repo_url", "https://github.com/me/app", "ref", "main", "size", "4x8", "secrets", List.of("npm-token")), last(stub).body(), "run body");
      eq("success", pl.waitFor("run_1", Duration.ofSeconds(5), Duration.ZERO).get("state"), "wait");
      pl.runs("running", 10);
      eq("/v1/pipelines/runs?state=running&limit=10", last(stub).path(), "runs path");
      pl.logs("run_1", "test", 2);
      eq("/v1/pipelines/runs/run_1/logs?job=test&after=2", last(stub).path(), "logs path");
      eq("cancelled", pl.cancel("run_1").get("state"), "cancel");
      eq("dist", pl.artifacts("run_1").get(0).get("path"), "artifacts");
      eq("tgz", new String(pl.downloadArtifact("run_1", "build", 0), java.nio.charset.StandardCharsets.UTF_8), "artifact bytes");
      eq("whsec", pl.createHook("https://github.com/me/app", m("cron", "0 3 * * *", "cron_ref", "main")).get("secret"), "hook");
      eq(1, pl.hooks().size(), "hooks");
      pl.deleteHook("hk_1");
      raises(IllegalArgumentException.class, () -> pl.run("", (Map<String, ?>) null));
    }
  }
}
