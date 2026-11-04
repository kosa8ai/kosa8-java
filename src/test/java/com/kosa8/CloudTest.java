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

  record Seen(String method, String path, Map<String, String> headers, Object body) {}

  record Answer(int status, Object body) {}

  /** A stub control plane on 127.0.0.1 answering with fn; every request is kept in seen. */
  static final class Stub implements AutoCloseable {
    final List<Seen> seen = new ArrayList<>();
    final HttpServer srv;

    Stub(Function<Seen, Answer> fn) throws IOException {
      srv = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      srv.createContext("/", x -> {
        String text = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> h = new LinkedHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        Seen s = new Seen(x.getRequestMethod(), x.getRequestURI().toString(), h, text.isEmpty() ? null : Json.parse(text));
        synchronized (seen) { seen.add(s); }
        Answer a = fn.apply(s);
        byte[] out = a.body() == null ? new byte[0] : Json.write(a.body()).getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
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
}
