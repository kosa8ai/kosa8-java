package com.kosa8;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Kosa8Cloud against a local HTTP stub of the control plane. */
public final class CloudTest extends ClientTest {
  static final String KEY = "k8c_live_testkey";

  record Seen(String method, String path, Map<String, String> headers, Object body, byte[] raw) {}

  /** body may be a byte[], sent as application/octet-stream. */
  record Answer(int status, Object body, Map<String, String> headers) {
    Answer(int status, Object body) { this(status, body, Map.of()); }
  }

  /** A stub control plane on 127.0.0.1 answering with fn; every request is kept in seen. */
  static final class Stub implements AutoCloseable {
    final List<Seen> seen = new ArrayList<>();
    final HttpServer srv;

    Stub(Function<Seen, Answer> fn) throws IOException {
      srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      srv.createContext("/", x -> {
        byte[] raw = x.getRequestBody().readAllBytes();
        String text = new String(raw, StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        boolean json = !"application/octet-stream".equals(h.get("content-type"));
        Seen s = new Seen(x.getRequestMethod(), x.getRequestURI().toString(), h, text.isEmpty() || !json ? null : Json.parse(text), raw);
        synchronized (seen) { seen.add(s); }
        Answer a = fn.apply(s);
        byte[] out = a.body() == null ? new byte[0]
            : a.body() instanceof byte[] bs ? bs : Json.write(a.body()).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", a.body() instanceof byte[] ? "application/octet-stream" : "application/json");
        a.headers().forEach((k, v) -> x.getResponseHeaders().set(k, v));
        x.sendResponseHeaders(a.status(), out.length == 0 ? -1 : out.length);
        if (out.length > 0) x.getResponseBody().write(out);
        x.close();
      });
      srv.start();
    }

    String url() { return "http://127.0.0.1:" + srv.getAddress().getPort(); }

    Kosa8Cloud cloud() { return Kosa8Cloud.builder().apiKey(KEY).baseUrl(url()).getenv(x -> null).build(); }

    @Override
    public void close() { srv.stop(0); }
  }

  static Map<String, Object> sbx(String state) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", "sbx_1");
    m.put("state", state);
    m.put("template", "python");
    m.put("cpus", 1);
    m.put("mem_mb", 512);
    return m;
  }

  public void testCloudKeyAndAddress() {
    Kosa8Cloud c = Kosa8Cloud.builder().getenv(Map.of("KOSA8_API_KEY", KEY)::get).build();
    eq("https://api.kosa8.com", c.baseUrl(), "default address");
    check(!c.toString().contains(KEY), "toString hides the key: " + c);
    eq("https://cp.example.com", Kosa8Cloud.builder().getenv(Map.of("KOSA8_API_KEY", KEY, "KOSA8_CLOUD_URL", "https://cp.example.com/")::get)
        .build().baseUrl(), "KOSA8_CLOUD_URL");
    var e = raises(Kosa8Exception.class, () -> Kosa8Cloud.builder().getenv(x -> null).build());
    check(e.getMessage().contains("KOSA8_API_KEY"), e.getMessage());
    e = raises(Kosa8Exception.class, () -> Kosa8Cloud.builder().apiKey("sk-other").getenv(x -> null).build());
    check(e.getMessage().contains("k8c_live_"), e.getMessage());
    e = raises(Kosa8Exception.class, () -> Kosa8Cloud.builder().apiKey(KEY).baseUrl("http://cp.example.com").getenv(x -> null).build());
    check(e.getMessage().contains("https"), e.getMessage());
    raises(Kosa8Exception.class, () -> Kosa8Cloud.builder().apiKey(KEY).baseUrl("not a url").getenv(x -> null).build());
    eq("http://localhost:8787", Kosa8Cloud.builder().apiKey(KEY).baseUrl("http://localhost:8787/").getenv(x -> null).build().baseUrl(), "localhost");
    eq("http://[::1]:8787", Kosa8Cloud.builder().apiKey(KEY).baseUrl("http://[::1]:8787").getenv(x -> null).build().baseUrl(), "::1");
  }

  public void testCloudSandboxLifecycle() throws Exception {
    try (Stub stub = new Stub(s -> {
      String u = s.path(), m = s.method();
      if (m.equals("POST") && u.equals("/v1/sandboxes")) return new Answer(201, Map.of("sandbox", sbx("running"), "host_ms", 6));
      if (u.endsWith("/exec")) return new Answer(200, Map.of("exit", 0, "stdout", "42\n", "stderr", "", "woke_ms", 70));
      if (u.endsWith("/sleep")) return new Answer(200, Map.of("sandbox", sbx("sleeping")));
      if (u.endsWith("/wake")) return new Answer(200, Map.of("sandbox", sbx("running")));
      if (m.equals("GET") && u.equals("/v1/sandboxes?all=1")) return new Answer(200, Map.of("sandboxes", List.of(sbx("deleted"))));
      if (m.equals("GET") && u.equals("/v1/sandboxes/sbx_1")) return new Answer(200, Map.of("sandbox", sbx("running")));
      if (m.equals("DELETE")) return new Answer(200, Map.of("ok", true, "id", "sbx_1"));
      return new Answer(404, Map.of("error", "not_found", "message", "no route"));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox box = cloud.sandboxes().create("python", null, 600);
      eq(Map.of("template", "python", "sleep_after_s", 600L), stub.seen.get(0).body(), "create body");
      eq("Bearer " + KEY, stub.seen.get(0).headers().get("authorization"), "bearer");
      eq("sbx_1", box.id(), "id");
      CloudExecResult r = box.exec("python3 -c \"print(6*7)\"", null, Map.of("A", "1"));
      eq(Map.of("cmd", List.of("sh", "-c", "python3 -c \"print(6*7)\""), "env", List.of("A=1")), stub.seen.get(1).body(), "exec body");
      eq(new CloudExecResult(0, "42\n", "", 70L), r, "exec result");
      box.exec(List.of("ls", "/"), "alpine", null);
      eq(Map.of("cmd", List.of("ls", "/"), "image", "alpine"), stub.seen.get(2).body(), "argv exec body");
      eq("sleeping", box.sleep().state(), "sleep");
      eq("running", box.wake().state(), "wake");
      eq("running", box.refresh().state(), "refresh");
      eq("deleted", cloud.sandboxes().list(true).get(0).state(), "list all");
      eq("sbx_1", cloud.sandboxes().get("sbx_1").id(), "get");
      box.delete();
      List<String> calls = new ArrayList<>();
      for (Seen s : stub.seen) calls.add(s.method() + " " + s.path());
      eq(List.of("POST /v1/sandboxes", "POST /v1/sandboxes/sbx_1/exec", "POST /v1/sandboxes/sbx_1/exec",
          "POST /v1/sandboxes/sbx_1/sleep", "POST /v1/sandboxes/sbx_1/wake", "GET /v1/sandboxes/sbx_1",
          "GET /v1/sandboxes?all=1", "GET /v1/sandboxes/sbx_1", "DELETE /v1/sandboxes/sbx_1"), calls, "calls");
      check(stub.seen.get(3).body() == null, "sleep sends no body");
    }
  }

  public void testCloudFunctions() throws Exception {
    try (Stub stub = new Stub(s -> {
      if (s.path().endsWith("/invoke")) return new Answer(200, Map.of("invocation", Map.of("id", "inv_1"), "ok", true,
          "result", Map.of("hello", "kosa8")));
      if (s.path().endsWith("/map")) return new Answer(200, Map.of("count", 2, "results", List.of()));
      if (s.path().equals("/v1/functions")) return new Answer(200, Map.of("functions", List.of(Map.of("name", "hello"))));
      return new Answer(200, Map.of("function", Map.of("name", "hello", "version", 1)));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      cloud.functions().deploy("hello", Map.of("runtime", "python3.12", "code", "def handler(e):\n    return e\n"));
      eq("PUT /v1/functions/hello", stub.seen.get(0).method() + " " + stub.seen.get(0).path(), "deploy route");
      eq("python3.12", ((Map<?, ?>) stub.seen.get(0).body()).get("runtime"), "deploy body");
      eq(Map.of("hello", "kosa8"), cloud.functions().invoke("hello", Map.of("name", "kosa8")).get("result"), "invoke");
      eq(Map.of("event", Map.of("name", "kosa8")), stub.seen.get(1).body(), "invoke body");
      cloud.functions().invoke("hello");
      check(((Map<?, ?>) stub.seen.get(2).body()).containsKey("event"), "null event still sent");
      cloud.functions().map("hello", List.of(1, 2), 4);
      eq(Map.of("events", List.of(1L, 2L), "concurrency", 4L), stub.seen.get(3).body(), "map body");
      eq("hello", cloud.functions().list().get(0).get("name"), "list");
      cloud.functions().get("a b");
      eq("/v1/functions/a%20b", stub.seen.get(5).path(), "name escaped");
      cloud.functions().delete("hello");
      eq("DELETE", stub.seen.get(6).method(), "delete");
    }
  }

  public void testCloudTypedErrorsKeepTheMessageAndCode() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(402, Map.of("error", "payment_required", "message", "add a payment method to start running work")),
        new Answer(429, Map.of("error", "quota_exceeded", "message", "this organization already has 20 active sandboxes")),
        new Answer(404, Map.of("error", "not_found", "message", "no such sandbox")),
        new Answer(401, Map.of("error", "unauthorized"))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      Kosa8Cloud cloud = stub.cloud();
      var p = raises(Kosa8Exception.PaymentRequiredException.class, () -> cloud.sandboxes().create());
      check(p instanceof Kosa8Exception.ApiException, "an ApiException");
      eq("payment_required", p.code(), "code");
      eq("add a payment method to start running work", p.getMessage(), "message");
      eq(402, p.status(), "status");
      eq("payment_required", ((Map<?, ?>) p.body()).get("error"), "raw body kept");
      var q = raises(Kosa8Exception.RateLimitException.class, () -> cloud.sandboxes().create());
      eq("quota_exceeded", q.code(), "429 code");
      var n = raises(Kosa8Exception.NotFoundException.class, () -> cloud.sandboxes().get("sbx_x"));
      eq("not_found", n.code(), "404 code");
      var a = raises(Kosa8Exception.AuthException.class, () -> cloud.me());
      eq("unauthorized", a.getMessage(), "no message: the code");
    }
  }

  public void testCloudUnreachable() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      port = s.getLocalPort();
    }
    Kosa8Cloud cloud = Kosa8Cloud.builder().apiKey(KEY).baseUrl("http://127.0.0.1:" + port).timeout(Duration.ofSeconds(5))
        .getenv(x -> null).build();
    var e = raises(Kosa8Exception.ConnectionException.class, cloud::me);
    check(e.getMessage().contains("could not reach kosa8 Cloud"), e.getMessage());
  }

  static List<String> calls(Stub stub) {
    List<String> calls = new ArrayList<>();
    for (Seen s : stub.seen) calls.add(s.method() + " " + s.path());
    return calls;
  }

  static Map<String, Object> sbx(String id, String state) {
    Map<String, Object> m = sbx(state);
    m.put("id", id);
    return m;
  }

  public void testCloudDefaultBaseUrlIsApiKosa8Com() {
    eq("https://api.kosa8.com", Kosa8Cloud.DEFAULT_CLOUD_URL, "constant");
    Kosa8Cloud c = Kosa8Cloud.builder().apiKey(KEY).getenv(x -> null).build();
    eq("https://api.kosa8.com", c.baseUrl(), "default with no env");
    eq("0.4.0", Kosa8Client.VERSION, "version");
  }

  public void testCloudCreateOptions() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(201, Map.of("sandbox", sbx("running"))))) {
      Kosa8Cloud cloud = stub.cloud();
      cloud.sandboxes().create(new CloudOptions.Create().template("python").region("eu-hel1").sleepAfterS(0).ttlSeconds(3600));
      eq(Map.of("template", "python", "region", "eu-hel1", "sleep_after_s", 0L, "ttl_s", 3600L), stub.seen.get(0).body(), "ttl body");
      cloud.sandboxes().create(new CloudOptions.Create().template("alice/agent@1.2.0").deleteAfter(1_900_000_000_000L).acceptPrice(4.5));
      eq(Map.of("template", "alice/agent@1.2.0", "delete_after", 1_900_000_000_000L, "accept_price", 4.5), stub.seen.get(1).body(),
          "delete_after + accept_price body");
      cloud.sandboxes().create(new CloudOptions.Create());
      eq(Map.of(), stub.seen.get(2).body(), "nothing set: empty object");
      check("{}".equals(new String(stub.seen.get(2).raw(), StandardCharsets.UTF_8)), "sends {}");
      eq("kosa8-java/0.4.0", stub.seen.get(0).headers().get("user-agent"), "user agent");
      int before = stub.seen.size();
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(59)));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(7_776_001)));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(60).deleteAfter(1L)));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().sleepAfterS(604_801)));
      raises(IllegalArgumentException.class, () -> cloud.sandboxes().create(new CloudOptions.Create().acceptPrice(-1)));
      eq(before, stub.seen.size(), "bad options send nothing");
      cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(60));
      cloud.sandboxes().create(new CloudOptions.Create().ttlSeconds(7_776_000));
      eq(before + 2, stub.seen.size(), "bounds are inclusive");
    }
  }

  public void testCloudUpdate() throws Exception {
    try (Stub stub = new Stub(s -> {
      Map<String, Object> sb = sbx("running");
      sb.put("delete_after", 1_900_000_000_000L);
      sb.put("sleep_after_s", 120);
      return new Answer(200, Map.of("sandbox", sb, "notes", List.of("sleep_after_s applies at the next start")));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox box = new CloudSandbox(cloud, sbx("running"));
      eq(null, box.deleteAfter(), "no lifetime yet");
      CloudSandbox same = box.update(new CloudOptions.Update().ttlSeconds(7200).sleepAfterS(120));
      check(same == box, "returns the same sandbox");
      eq("PATCH /v1/sandboxes/sbx_1", calls(stub).get(0), "route");
      eq(Map.of("ttl_s", 7200L, "sleep_after_s", 120L), stub.seen.get(0).body(), "body");
      eq(1_900_000_000_000L, box.deleteAfter(), "info refreshed");
      eq(120, box.sleepAfterS(), "sleepAfterS");
      box.update(new CloudOptions.Update().clearLifetime());
      eq("{\"ttl_s\":null}", new String(stub.seen.get(1).raw(), StandardCharsets.UTF_8), "clearLifetime sends ttl_s null");
      box.update(new CloudOptions.Update().deleteAfter(1_900_000_000_000L));
      eq(Map.of("delete_after", 1_900_000_000_000L), stub.seen.get(2).body(), "delete_after");
      raises(IllegalArgumentException.class, () -> box.update(new CloudOptions.Update()));
      raises(IllegalArgumentException.class, () -> box.update(null));
      raises(IllegalArgumentException.class, () -> box.update(new CloudOptions.Update().clearLifetime().ttlSeconds(60)));
      raises(IllegalArgumentException.class, () -> box.update(new CloudOptions.Update().ttlSeconds(10)));
      raises(IllegalArgumentException.class, () -> box.update(new CloudOptions.Update().ttlSeconds(60).deleteAfter(5L)));
      eq(3, stub.seen.size(), "bad updates send nothing");
    }
  }

  public void testCloudListPagination() throws Exception {
    try (Stub stub = new Stub(s -> {
      String u = s.path();
      if (u.contains("cursor=c2")) return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_3", "running"))));
      if (u.contains("cursor=c1")) return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_2", "running")), "next_cursor", "c2"));
      if (u.contains("loop")) return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_L", "running")), "next_cursor", "same"));
      if (u.contains("cursor=same")) return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_S", "running")), "next_cursor", "same"));
      if (u.contains("limit=")) return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_1", "running")), "next_cursor", "c1"));
      return new Answer(200, Map.of("sandboxes", List.of(sbx("sbx_1", "running"), sbx("sbx_2", "running")), "next_cursor", ""));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandboxPage page = cloud.sandboxes().listPage(new CloudOptions.List().all(true).limit(1));
      eq("GET /v1/sandboxes?all=1&limit=1", calls(stub).get(0), "page query");
      eq("c1", page.nextCursor(), "next cursor");
      check(page.hasNext(), "hasNext");
      eq(1, page.sandboxes().size(), "one");
      CloudSandboxPage last = cloud.sandboxes().listPage();
      eq(null, last.nextCursor(), "empty cursor is the end");
      eq(2, last.sandboxes().size(), "unpaged server: everything");
      eq("GET /v1/sandboxes", calls(stub).get(1), "no query");
      eq(1, cloud.sandboxes().list(new CloudOptions.List().limit(1)).size(), "list(options)");
      stub.seen.clear();
      List<String> ids = new ArrayList<>();
      for (CloudSandbox b : cloud.sandboxes().listAll(new CloudOptions.List().limit(1))) ids.add(b.id());
      eq(List.of("sbx_1", "sbx_2", "sbx_3"), ids, "listAll follows cursors");
      eq(List.of("GET /v1/sandboxes?limit=1", "GET /v1/sandboxes?limit=1&cursor=c1", "GET /v1/sandboxes?limit=1&cursor=c2"),
          calls(stub), "listAll calls");
      stub.seen.clear();
      List<CloudSandbox> looped = cloud.sandboxes().listAll(new CloudOptions.List().cursor("loop"));
      eq(List.of("sbx_L", "sbx_S"), looped.stream().map(CloudSandbox::id).toList(), "stops when a cursor repeats");
      eq(2, stub.seen.size(), "two requests then stop");
      eq(2, cloud.sandboxes().listAll().size(), "listAll on an unpaged server");
      raises(IllegalArgumentException.class, () -> new CloudOptions.List().limit(0));
      cloud.sandboxes().listPage(new CloudOptions.List().cursor("a b/c"));
      eq("GET /v1/sandboxes?cursor=a%20b%2Fc", calls(stub).get(calls(stub).size() - 1), "cursor escaped");
    }
  }

  public void testCloudFiles() throws Exception {
    byte[] bin = {0, 1, 2, (byte) 0xff, 10};
    try (Stub stub = new Stub(s -> {
      String u = s.path(), m = s.method();
      if (m.equals("GET") && u.startsWith("/v1/sandboxes/sbx_1/files/list")) return new Answer(200, Map.of("path", "/workspace", "files",
          List.of(Map.of("name", "a.txt", "path", "/workspace/a.txt", "type", "file", "size", 5, "mode", "-rw-r--r--", "mod_time", "2026-11-12T00:00:00Z"),
              Map.of("name", "d", "path", "/workspace/d", "type", "dir", "size", 4096, "mode", "drwxr-xr-x", "mod_time", "x"))));
      if (m.equals("GET") && u.startsWith("/v1/sandboxes/sbx_1/files?")) return new Answer(200, bin);
      if (m.equals("PUT")) return new Answer(200, Map.of("file", Map.of("name", "a b.txt", "path", "/workspace/a b.txt", "type", "file",
          "size", s.raw().length)));
      if (m.equals("POST") && u.endsWith("/files/mkdir")) return new Answer(200, Map.of("file", Map.of("path", "/workspace/d", "type", "dir")));
      if (m.equals("DELETE")) return new Answer(200, Map.of("removed", "/workspace/d"));
      return new Answer(404, Map.of("error", "not_found", "message", "no route " + u));
    })) {
      CloudSandbox box = new CloudSandbox(stub.cloud(), sbx("running"));
      byte[] got = box.files().read("/workspace/a b.bin");
      check(java.util.Arrays.equals(bin, got), "raw bytes back");
      eq("GET /v1/sandboxes/sbx_1/files?path=%2Fworkspace%2Fa%20b.bin", calls(stub).get(0), "read route, path escaped");
      eq("application/octet-stream", stub.seen.get(0).headers().get("accept"), "accept octet-stream");
      eq(new String(bin, StandardCharsets.UTF_8), box.files().readText("/workspace/x"), "readText");
      CloudFileInfo w = box.files().write("/workspace/a b.txt", "héllo");
      eq("PUT /v1/sandboxes/sbx_1/files?path=%2Fworkspace%2Fa%20b.txt", calls(stub).get(2), "write route");
      eq("application/octet-stream", stub.seen.get(2).headers().get("content-type"), "upload content type");
      check(java.util.Arrays.equals("héllo".getBytes(StandardCharsets.UTF_8), stub.seen.get(2).raw()), "raw upload body");
      eq(6L, w.size(), "file info size");
      eq("file", w.type(), "type");
      box.files().write("/workspace/b.bin", bin);
      check(java.util.Arrays.equals(bin, stub.seen.get(3).raw()), "bytes upload body");
      List<CloudFileInfo> ls = box.files().list();
      eq("GET /v1/sandboxes/sbx_1/files/list?path=%2Fworkspace", calls(stub).get(4), "list default /workspace");
      eq(new CloudFileInfo("a.txt", "/workspace/a.txt", "file", 5L, "-rw-r--r--", "2026-11-12T00:00:00Z"), ls.get(0), "entry");
      check(ls.get(1).isDir(), "dir");
      box.files().list("/tmp");
      eq("GET /v1/sandboxes/sbx_1/files/list?path=%2Ftmp", calls(stub).get(5), "list path");
      CloudFileInfo d = box.files().mkdir("/workspace/d");
      eq(Map.of("path", "/workspace/d"), stub.seen.get(6).body(), "mkdir body");
      check(d.isDir(), "mkdir info");
      box.files().delete("/workspace/a.txt");
      eq("DELETE /v1/sandboxes/sbx_1/files?path=%2Fworkspace%2Fa.txt", calls(stub).get(7), "delete");
      box.files().delete("/workspace/d", true);
      eq("DELETE /v1/sandboxes/sbx_1/files?path=%2Fworkspace%2Fd&recursive=1", calls(stub).get(8), "delete recursive");
      raises(IllegalArgumentException.class, () -> box.files().read(""));
      raises(IllegalArgumentException.class, () -> box.files().mkdir(null));
      eq(9, stub.seen.size(), "bad paths send nothing");
    }
  }

  public void testCloudFileReadErrorIsTyped() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(404, Map.of("error", "not_found", "message", "no such file")))) {
      CloudSandbox box = new CloudSandbox(stub.cloud(), sbx("running"));
      var e = raises(Kosa8Exception.NotFoundException.class, () -> box.files().read("/workspace/nope"));
      eq("no such file", e.getMessage(), "error body parsed, not returned as bytes");
    }
  }

  public void testCloudCheckpointsRewindForkPorts() throws Exception {
    Map<String, Object> cp = Map.of("id", "cp_1", "label", "clean", "auto", false, "created_at", "2026-11-12T10:00:00Z", "bytes", 1024);
    Map<String, Object> backup = Map.of("id", "cp_2", "label", "before rewind", "auto", true, "bytes", 2048);
    try (Stub stub = new Stub(s -> {
      String u = s.path(), m = s.method();
      if (m.equals("POST") && u.endsWith("/checkpoints")) return new Answer(201, Map.of("checkpoint", cp, "evicted", List.of(), "ms", 80));
      if (m.equals("GET") && u.endsWith("/checkpoints")) return new Answer(200, Map.of("checkpoints", List.of(cp, backup), "max", 10));
      if (u.endsWith("/rewind")) return new Answer(200, Map.of("sandbox", sbx("running"), "checkpoint", cp, "backup", backup, "ms", 95));
      if (u.endsWith("/fork")) {
        Map<String, Object> a = sbx("sbx_f1", "running");
        a.put("parent_id", "sbx_1");
        a.put("source", "fork");
        return new Answer(201, Map.of("parent", "sbx_1", "sandboxes", List.of(a, sbx("sbx_f2", "running")), "host_ms", 300));
      }
      if (u.endsWith("/ports")) return new Answer(200, Map.of("ports", List.of(Map.of("port", 8000, "address", "0.0.0.0", "container", "c1", "loopback", false))));
      return new Answer(404, Map.of("error", "not_found", "message", "no route"));
    })) {
      CloudSandbox box = new CloudSandbox(stub.cloud(), sbx("sleeping"));
      eq(new CloudCheckpoint("cp_1", "clean", false, "2026-11-12T10:00:00Z", 1024L), box.checkpoint("clean"), "checkpoint");
      eq(Map.of("label", "clean"), stub.seen.get(0).body(), "label body");
      box.checkpoint();
      check("{}".equals(new String(stub.seen.get(1).raw(), StandardCharsets.UTF_8)), "no label: {}");
      raises(IllegalArgumentException.class, () -> box.checkpoint("x".repeat(201)));
      List<CloudCheckpoint> list = box.checkpoints();
      eq(2, list.size(), "checkpoints");
      check(list.get(1).auto(), "auto");
      CloudRewindResult r = box.rewind("cp_1");
      eq(Map.of("checkpoint_id", "cp_1"), stub.seen.get(3).body(), "rewind body");
      eq("cp_1", r.checkpoint().id(), "rewound to");
      eq("cp_2", r.backup().id(), "backup");
      eq(95L, r.ms(), "ms");
      eq("running", box.state(), "info updated by rewind");
      raises(IllegalArgumentException.class, () -> box.rewind(""));
      List<CloudSandbox> forks = box.fork(2);
      eq(Map.of("n", 2L), stub.seen.get(4).body(), "fork body");
      eq(2, forks.size(), "two forks");
      eq("sbx_1", forks.get(0).parentId(), "parentId");
      eq("fork", forks.get(0).source(), "source");
      box.fork();
      eq(Map.of("n", 1L), stub.seen.get(5).body(), "fork default 1");
      raises(IllegalArgumentException.class, () -> box.fork(0));
      raises(IllegalArgumentException.class, () -> box.fork(101));
      eq(List.of(new CloudPort(8000, "0.0.0.0", "c1", false)), box.ports(), "ports");
      eq(List.of("POST /v1/sandboxes/sbx_1/checkpoints", "POST /v1/sandboxes/sbx_1/checkpoints", "GET /v1/sandboxes/sbx_1/checkpoints",
          "POST /v1/sandboxes/sbx_1/rewind", "POST /v1/sandboxes/sbx_1/fork", "POST /v1/sandboxes/sbx_1/fork",
          "GET /v1/sandboxes/sbx_1/ports"), calls(stub), "calls");
    }
  }

  public void testCloudTemplatesAndTypedInfo() throws Exception {
    try (Stub stub = new Stub(s -> new Answer(200, Map.of("templates", List.of(Map.of("key", "base", "cpus", 1, "mem_mb", 512),
        Map.of("key", "python", "image", "python:3.12-alpine")), "default_template", "base")))) {
      List<Map<String, Object>> t = stub.cloud().templates();
      eq("GET /v1/templates", calls(stub).get(0), "route");
      eq(List.of("base", "python"), List.of(t.get(0).get("key"), t.get(1).get("key")), "keys");
    }
    Map<String, Object> info = new LinkedHashMap<>(sbx("running"));
    info.put("region", "eu-hel1");
    info.put("delete_after", 1_900_000_000_000L);
    info.put("sleep_after_s", 300L);
    info.put("host_unreachable", true);
    info.put("own_host", true);
    info.put("origin_item_version", "1.2.0");
    info.put("cost_usd", 0.25);
    info.put("created_at", 1_800_000_000_000L);
    CloudSandbox b = new CloudSandbox(null, info);
    eq("python", b.template(), "template");
    eq("eu-hel1", b.region(), "region");
    eq(null, b.parentId(), "no parent");
    eq(300, b.sleepAfterS(), "sleepAfterS");
    check(b.hostUnreachable() && b.ownHost(), "flags");
    eq("1.2.0", b.originItemVersion(), "originItemVersion");
    eq(0.25, b.costUsd(), "costUsd");
    eq(1_800_000_000_000L, b.createdAt(), "createdAt");
  }

  public void testCloudNewErrorMappings() throws Exception {
    List<Answer> answers = new ArrayList<>(List.of(
        new Answer(402, Map.of("error", "spending_cap_reached", "message", "the spending cap is reached",
            "upgrade_url", "https://app.kosa8.com/billing", "agent_message", "Tell your person to raise the cap.")),
        new Answer(402, Map.of("error", "payment_required", "message", "add a payment method")),
        new Answer(423, Map.of("error", "org_frozen", "message", "this organization is frozen")),
        new Answer(429, Map.of("error", "rate_limited", "message", "slow down"), Map.of("Retry-After", "7")),
        new Answer(429, Map.of("error", "quota_exceeded", "message", "too many sandboxes")),
        new Answer(502, Map.of("error", "host_failed", "message", "exec failed on the host: host unreachable: timed out")),
        new Answer(504, Map.of("error", "exec_timeout", "message", "the command ran past 30 s")),
        new Answer(408, Map.of("error", "exec_timeout", "message", "the command ran past 30 s")),
        new Answer(502, Map.of("error", "host_failed", "message", "sleep failed: timed out")),
        new Answer(501, Map.of("error", "not_supported", "message", "this host has no data plane")),
        new Answer(501, Map.of("error", "not_available", "message", "no data plane"))));
    try (Stub stub = new Stub(s -> answers.remove(0))) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox box = new CloudSandbox(cloud, sbx("running"));
      var p = raises(Kosa8Exception.PaymentRequiredException.class, () -> cloud.sandboxes().create());
      eq("spending_cap_reached", p.code(), "402 code");
      eq("https://app.kosa8.com/billing", p.upgradeUrl(), "upgradeUrl");
      eq("Tell your person to raise the cap.", p.agentMessage(), "agentMessage");
      var p2 = raises(Kosa8Exception.PaymentRequiredException.class, () -> cloud.sandboxes().create());
      eq(null, p2.upgradeUrl(), "no upgradeUrl");
      eq(null, p2.agentMessage(), "no agentMessage");
      var f = raises(Kosa8Exception.OrgFrozenException.class, () -> cloud.sandboxes().create());
      check(f instanceof Kosa8Exception.ApiException, "OrgFrozen is an ApiException");
      eq("org_frozen", f.code(), "423 code");
      eq(423, f.status(), "423");
      var r = raises(Kosa8Exception.RateLimitException.class, () -> cloud.sandboxes().create());
      eq(java.util.Optional.of(Duration.ofSeconds(7)), r.retryAfter(), "retryAfter");
      var r2 = raises(Kosa8Exception.RateLimitException.class, () -> cloud.sandboxes().create());
      eq(java.util.Optional.empty(), r2.retryAfter(), "no Retry-After");
      eq("quota_exceeded", r2.code(), "quota code");
      var t = raises(Kosa8Exception.ExecTimeoutException.class, () -> box.exec("sleep 60"));
      check(t instanceof Kosa8Exception.ApiException, "ExecTimeout is an ApiException");
      eq("host_failed", t.code(), "502 keeps its code");
      check(t.getMessage().contains("timed out") && t.getMessage().contains("30 s") && t.getMessage().contains("background"), t.getMessage());
      var t2 = raises(Kosa8Exception.ExecTimeoutException.class, () -> box.exec("sleep 60"));
      eq(504, t2.status(), "504");
      eq("exec_timeout", t2.code(), "504 code");
      var t3 = raises(Kosa8Exception.ExecTimeoutException.class, () -> box.exec("sleep 60"));
      eq(408, t3.status(), "exec_timeout code on any status");
      var notExec = raises(Kosa8Exception.ApiException.class, box::sleep);
      check(!(notExec instanceof Kosa8Exception.ExecTimeoutException), "502 timed out off /exec stays an ApiException");
      var u = raises(Kosa8Exception.UnsupportedException.class, () -> box.files().list());
      eq("not_supported", u.code(), "501 not_supported");
      var u2 = raises(Kosa8Exception.UnsupportedException.class, box::checkpoints);
      eq("not_available", u2.code(), "501 not_available");
    }
  }

  public void testCloudRetryAfterParsing() {
    eq(Duration.ofMillis(1500), Kosa8Cloud.parseRetryAfter("1.5"), "decimal seconds");
    eq(null, Kosa8Cloud.parseRetryAfter(null), "absent");
    eq(null, Kosa8Cloud.parseRetryAfter("soon"), "unreadable");
    eq(Duration.ZERO, Kosa8Cloud.parseRetryAfter("Wed, 21 Oct 2015 07:28:00 GMT"), "a past date is zero");
    Duration d = Kosa8Cloud.parseRetryAfter(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(
        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(120)));
    check(d != null && d.getSeconds() > 100 && d.getSeconds() <= 120, "a future date: " + d);
  }

  public void testCloudExecClientTimeoutIsExecTimeout() throws Exception {
    try (Stub stub = new Stub(s -> {
      try {
        Thread.sleep(1500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new Answer(200, Map.of("exit", 0, "stdout", "", "stderr", ""));
    })) {
      Kosa8Cloud cloud = Kosa8Cloud.builder().apiKey(KEY).baseUrl(stub.url()).timeout(Duration.ofMillis(300)).getenv(x -> null).build();
      CloudSandbox box = new CloudSandbox(cloud, sbx("running"));
      var e = raises(Kosa8Exception.ExecTimeoutException.class, () -> box.exec("sleep 60"));
      eq("exec_timeout", e.code(), "code");
      raises(Kosa8Exception.TimeoutException.class, cloud::me);
    }
  }
}
