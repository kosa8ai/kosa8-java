package com.kosa8;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests: run with {@code ./test.sh}. Each public void test* method is one test. */
public final class ClientTest {
  static void check(boolean ok, String what) {
    if (!ok) throw new AssertionError(what);
  }

  static void eq(Object want, Object got, String what) {
    if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": want " + want + ", got " + got);
  }

  @SuppressWarnings("unchecked")
  static <T extends Throwable> T raises(Class<T> type, Runnable r) {
    try {
      r.run();
    } catch (Throwable e) {
      if (type.isInstance(e)) return (T) e;
      throw new AssertionError("want " + type.getSimpleName() + ", got " + e, e);
    }
    throw new AssertionError("want " + type.getSimpleName() + ", nothing thrown");
  }

  MockDaemon d;
  Kosa8Client k;

  public void testHealthAndHeaders() {
    d.on("GET", "/kosa8/v1/health", r -> MockDaemon.json(200, Map.of("status", "ok", "version", "0.12.0")));
    Kosa8Client c = Kosa8Client.builder().socketPath(d.socketPath).actor("agent").agentToken("at-1")
        .checkpoints(new MemoryCheckpointStore()).getenv(x -> null).build();
    eq("0.12.0", c.version(), "version");
    eq("agent", d.last().headers().get("x-kosa8-actor"), "actor");
    eq("at-1", d.last().headers().get("x-kosa8-agent-token"), "agent token");
    check(!d.last().headers().containsKey("authorization"), "no bearer over the local socket");
    raises(Kosa8Exception.class, () -> Kosa8Client.builder().socketPath(d.socketPath).actor("root").getenv(x -> null).build());
  }

  public void testCreateSandboxBody() {
    d.on("POST", "/kosa8/v1/sandboxes", r -> MockDaemon.json(201, Map.of("sandbox", Map.of("id", "demo"))));
    k.createSandbox(new Options.Create().name("demo").cpus(2).egressAllow(List.of(" pypi.org", "github.com"))
        .labels(Map.of("team", "a")).approval("sig"));
    Map<String, Object> b = d.last().json();
    eq("allow:pypi.org,github.com", b.get("egress"), "egress");
    eq(2L, b.get("cpus"), "cpus");
    check(!b.containsKey("mem_mb"), "unset fields omitted");
    eq("sig", d.last().headers().get("x-kosa8-approval"), "approval header");
    raises(Kosa8Exception.class, () -> k.createSandbox(new Options.Create().egress("any").egressAllow(List.of("x"))));
  }

  public void testTypedErrors() {
    d.on("POST", "/kosa8/v1/sandboxes/x/up", r -> MockDaemon.json(403, Map.of("error", "policy needs approval",
        "remediation", "sign it", "approval", Map.of("why", "egress any"))));
    d.on("POST", "/kosa8/v1/sandboxes/x/down", r -> MockDaemon.json(409, Map.of("error", "already stopped")));
    var e = raises(Kosa8Exception.ApprovalRequiredException.class, () -> k.startSandbox("x"));
    check(e instanceof Kosa8Exception.PolicyException, "approval is a policy error");
    eq("policy needs approval (sign it)", e.getMessage(), "message");
    eq("egress any", e.approval().get("why"), "approval");
    eq(403, e.status(), "status");
    raises(Kosa8Exception.ConflictException.class, () -> k.stopSandbox("x"));
    raises(Kosa8Exception.NotFoundException.class, () -> k.sandboxEgress("missing"));
  }

  public void testConnectionError() {
    Kosa8Client c = Kosa8Client.builder().socketPath("/tmp/k8j-nope.sock").checkpoints(new MemoryCheckpointStore()).getenv(x -> null).build();
    var e = raises(Kosa8Exception.ConnectionException.class, c::health);
    check(e.getMessage().contains("kosa8 daemon start"), "hint: " + e.getMessage());
  }

  public void testRunStreams() {
    d.on("POST", "/kosa8/v1/sandboxes/sb/run", r -> MockDaemon.frames(MockDaemon.frame("status", "pulling"),
        MockDaemon.frame("stdout", "hel"), MockDaemon.frame("stderr", "warn"), MockDaemon.frame("stdout", "lo\n"),
        MockDaemon.done("ok", true, "exit", 3)));
    List<String> live = new ArrayList<>();
    Results.Exec r = k.run("sb", "alpine", List.of("echo"), new Options.Run().env(Map.of("A", "1"))
        .onStdout(b -> live.add(new String(b, StandardCharsets.UTF_8))));
    eq("hello\n", r.stdout(), "stdout");
    eq("warn", r.stderr(), "stderr");
    eq(3, r.exitCode(), "exit");
    check(!r.ok(), "not ok");
    eq(List.of("hel", "lo\n"), live, "live chunks");
    eq(List.of("A=1"), d.last().json().get("env"), "env");
  }

  public void testStreamErrorAndTruncation() {
    d.on("POST", "/kosa8/v1/sandboxes/sb/exec", r -> MockDaemon.frames(MockDaemon.frame("stdout", "partial"),
        MockDaemon.done("error", "container gone")));
    d.on("POST", ".*/containers/c1/attach", r -> MockDaemon.frames(MockDaemon.frame("stdout", "cut")));
    var e = raises(Kosa8Exception.StreamException.class, () -> k.exec("sb", "c1", List.of("ls")));
    eq("partial", e.stdout(), "output so far");
    eq("container gone", e.getMessage(), "message");
    e = raises(Kosa8Exception.StreamException.class, () -> k.attach("sb", "c1", null));
    check(e.getMessage().contains("no final frame"), e.getMessage());
    eq("cut", e.stdout(), "truncated output");
  }

  public void testStdinChunkedAndEscaped() {
    d.on("POST", ".*/stdin", r -> MockDaemon.json(200, Map.of("bytes", r.body().length)));
    InputStream in = new SequenceInputStream(new ByteArrayInputStream("abc".getBytes()), new ByteArrayInputStream("de".getBytes()));
    eq(5L, k.writeStdin("sb", "c/1", in, null).get("bytes"), "bytes");
    eq("chunked", d.last().headers().get("transfer-encoding"), "chunked");
    eq("application/octet-stream", d.last().headers().get("content-type"), "content type");
    check(d.last().rawTarget().contains("c%2F1"), "escaped: " + d.last().rawTarget());
    eq(3L, k.writeStdin("sb", "c1", "xyz".getBytes(), null).get("bytes"), "fixed body");
  }

  public void testForkAndExplore() {
    d.on("POST", "/kosa8/v1/sandboxes/p/pull", r -> MockDaemon.json(200, Map.of()));
    d.on("POST", "/kosa8/v1/sandboxes/p/fork", r -> MockDaemon.json(200, Map.of("forks",
        List.of(Map.of("id", "e-1"), Map.of("id", "e-2"), Map.of("id", "e-3")), "fork_ms", 412, "count", 3)));
    var slow = new java.util.LinkedHashMap<>(MockDaemon.frame("stdout", "slow"));
    slow.put("_sleep", 1500);
    d.on("POST", "/kosa8/v1/sandboxes/e-1/run", r -> MockDaemon.frames(slow, MockDaemon.done("exit", 0)));
    AtomicInteger tries = new AtomicInteger();
    d.on("POST", "/kosa8/v1/sandboxes/e-2/run", r -> tries.incrementAndGet() == 1
        ? MockDaemon.frames(MockDaemon.done("error", "connection refused")) // engine still settling
        : MockDaemon.frames(MockDaemon.frame("stdout", "fast"), MockDaemon.done("exit", 0)));
    d.on("POST", "/kosa8/v1/sandboxes/e-3/run", r -> MockDaemon.frames(MockDaemon.frame("stdout", "fail"), MockDaemon.done("exit", 1)));
    List<String> deleted = Collections.synchronizedList(new ArrayList<>());
    d.on("DELETE", "/kosa8/v1/sandboxes/e-\\d", r -> {
      deleted.add(r.path());
      return MockDaemon.json(200, Map.of());
    });
    Results.Fork f = k.fork("p", 3, new Options.Fork().prefix("e"));
    eq(3, f.count(), "count");
    eq(412L, f.forkMs(), "fork_ms");
    Results.Explore res = k.explore("p", List.of("a", "b", "c"), new Options.Explore().prefix("e"));
    eq("e-2", res.winner(), "winner");
    eq(2, res.removed(), "removed");
    eq("fast", res.branches().get(1).output(), "output");
    eq(1, res.branches().get(2).exit(), "failed exit");
    eq(2, tries.get(), "transient failure retried");
    raises(Kosa8Exception.class, () -> k.explore("p", List.of("only"), null));
  }

  void checkpointRoutes(List<String> log) {
    d.on("POST", "/kosa8/v1/sandboxes/sb/snapshot", r -> {
      String id = (String) r.json().get("id");
      log.add("snap " + id);
      return MockDaemon.json(200, Map.of("id", id));
    });
    d.on("DELETE", "/kosa8/v1/sandboxes/sb", r -> {
      log.add("del sb");
      return MockDaemon.json(200, Map.of("deleted", "sb"));
    });
    d.on("DELETE", "/kosa8/v1/snapshots/.*", r -> {
      log.add("dsnap " + r.path().substring(r.path().lastIndexOf('/') + 1));
      return MockDaemon.json(200, Map.of());
    });
    d.on("POST", ".*/restore", r -> {
      String[] p = r.path().split("/");
      log.add("restore " + p[p.length - 2]);
      return MockDaemon.json(200, Map.of("sandbox", Map.of("id", "sb"), "restore_ms", 897));
    });
  }

  public void testCheckpointRewind() {
    List<String> log = Collections.synchronizedList(new ArrayList<>());
    checkpointRoutes(log);
    for (String l : List.of("base", "deps", "broken")) k.checkpoint("sb", l);
    Results.Rewind rw = k.rewind("sb", "DEPS");
    eq("sb-ckpt-2", rw.checkpoint().id(), "target");
    eq(List.of("sb-ckpt-3"), rw.dropped(), "dropped");
    eq(897L, rw.restoreMs(), "restore_ms");
    check(String.join(",", log).contains("del sb,restore sb-ckpt-2,dsnap sb-ckpt-3"), "order " + log);
    eq(2, k.listCheckpoints("sb").size(), "left");
    eq("sb-ckpt-3", k.checkpoint("sb", "").checkpoint().id(), "next id");
    raises(Kosa8Exception.CheckpointException.class, () -> k.rewind("sb", "nope"));
    raises(Kosa8Exception.CheckpointException.class, () -> k.checkpoint("../etc", ""));
  }

  public void testEvictionAndDelete() {
    List<String> log = Collections.synchronizedList(new ArrayList<>());
    checkpointRoutes(log);
    for (int i = 0; i < 11; i++) k.checkpoint("sb", "");
    List<Checkpoint> l = k.listCheckpoints("sb");
    eq(10, l.size(), "capped");
    eq("sb-ckpt-2", l.get(0).id(), "oldest evicted");
    check(String.join(",", log).contains("snap sb-ckpt-11,dsnap sb-ckpt-1"), "evict after replacement exists");
    Map<String, Object> out = k.sandbox("sb").delete(new Options.Delete().wipe(true));
    eq(10, out.get("checkpoints_dropped"), "dropped");
    eq("sb", out.get("deleted"), "daemon answer kept");
    check(d.last().rawTarget().contains("wipe=1"), d.last().rawTarget());
    check(k.listCheckpoints("sb").isEmpty(), "forgotten");
  }

  public void testFileStoreSharedWithCli() throws Exception {
    Path dir = Files.createTempDirectory("k8jstore");
    Path path = dir.resolve("checkpoints.json");
    Files.writeString(path, "{\"sb\":[{\"id\":\"sb-ckpt-4\",\"sandbox\":\"sb\",\"label\":\"cli\",\"created\":\"2026-11-01T10:00:00.123456789+05:30\"}]}");
    CheckpointBook book = new CheckpointBook(new FileCheckpointStore(path));
    eq("sb-ckpt-5", book.nextId("sb").id(), "next id after the CLI's");
    List<Thread> ts = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      int n = i;
      Thread t = new Thread(() -> book.record("other-ckpt-" + n, "other", "", List.of()));
      ts.add(t);
      t.start();
    }
    for (Thread t : ts) t.join();
    eq(8, book.list("other").size(), "concurrent records");
    Checkpoint cli = book.list("sb").get(0);
    eq("cli", cli.label(), "label");
    eq(123456789, cli.created().getNano(), "nanoseconds");
    eq("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)), "mode");
    check(Files.exists(dir.resolve("checkpoints.json.lock")), "lock file");
    check(!Files.readString(path).contains("\"auto\""), "auto omitted when false");
  }

  public void testResolveEndpoint() {
    eq("unix:///s.sock", Endpoint.resolve(null, "/s.sock", null, false, Endpoint.env(Map.of())).target(), "default");
    Endpoint e = Endpoint.resolve(null, "/s.sock", null, false, Endpoint.env(Map.of("KOSA8_HOST", "https://w.example.com/", "KOSA8_TOKEN", "t")));
    eq("https://w.example.com:443", e.target(), "env host");
    eq("t", e.token(), "env token");
    eq("http://127.0.0.1:7070", Endpoint.resolve("tcp://127.0.0.1:7070", null, null, false, Endpoint.env(Map.of())).target(), "tcp");
    eq("http://localhost:7070", Endpoint.resolve("localhost:7070", null, null, false, Endpoint.env(Map.of())).target(), "bare");
    eq("unix:///o.sock", Endpoint.resolve("unix:///o.sock", null, null, false, Endpoint.env(Map.of())).target(), "unix");
    raises(Kosa8Exception.class, () -> Endpoint.resolve("http://10.0.0.5:7070", null, null, false, Endpoint.env(Map.of())));
    eq("http://10.0.0.5:7070", Endpoint.resolve(null, null, null, false,
        Endpoint.env(Map.of("KOSA8_HOST", "http://10.0.0.5:7070", "KOSA8_INSECURE", "1"))).target(), "insecure");
    raises(Kosa8Exception.class, () -> Endpoint.resolve("ftp://x", null, null, false, Endpoint.env(Map.of())));
    eq(null, Endpoint.resolve("https://w.example.com", null, null, false, Endpoint.env(Map.of("KOSA8_TOKEN", "t"))).token(),
        "env token only for an env host");
  }

  public void testRemoteBearerAndTimeout() throws Exception {
    try (ServerSocket srv = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
      String[] auth = new String[1];
      Thread t = new Thread(() -> {
        try (Socket c = srv.accept()) {
          var in = c.getInputStream();
          String l;
          while ((l = Transport.readLine(in)) != null && !l.isEmpty())
            if (l.toLowerCase().startsWith("authorization:")) auth[0] = l.substring(14).strip();
          c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"version\":\"x\"}".getBytes());
        } catch (Exception ignored) {
          // test fails on the assertion below
        }
      });
      t.start();
      Kosa8Client c = Kosa8Client.builder().host("http://127.0.0.1:" + srv.getLocalPort()).token("tok")
          .checkpoints(new MemoryCheckpointStore()).getenv(x -> null).build();
      eq("x", c.version(), "remote version");
      t.join();
      eq("Bearer tok", auth[0], "bearer");
    }
    d.on("POST", "/kosa8/v1/sandboxes/sb/run", r -> {
      var f = new java.util.LinkedHashMap<>(MockDaemon.frame("stdout", "late"));
      f.put("_sleep", 2000);
      return MockDaemon.frames(f, MockDaemon.done("exit", 0));
    });
    raises(Kosa8Exception.TimeoutException.class,
        () -> k.run("sb", "alpine", List.of(), new Options.Run().timeout(Duration.ofMillis(300))));
  }

  @SuppressWarnings("unchecked")
  public void testFixesMountsCaptureExecEnvStdin() {
    d.on("POST", "/kosa8/v1/sandboxes", r -> MockDaemon.json(201, Map.of("ok", true)));
    k.createSandbox(new Options.Create().mounts(List.of("/src:/work:ro", "/d:/d")).capture("/tmp/pcap"));
    Map<String, Object> b = d.last().json();
    List<Map<String, Object>> ms = (List<Map<String, Object>>) b.get("mounts");
    eq("/work", ms.get(0).get("guest_path"), "guest path");
    eq(true, ms.get(0).get("read_only"), "ro");
    eq(false, ms.get(1).get("read_only"), "rw");
    eq("/tmp/pcap", ((Map<String, Object>) b.get("capture")).get("path"), "capture path");
    raises(Kosa8Exception.class, () -> k.createSandbox(new Options.Create().mounts(List.of("nocolon"))));
    raises(Kosa8Exception.class, () -> k.exec("s", "c", List.of("true"), new Options.Run().env(List.of("A=1"))));
    d.on("POST", "/kosa8/v1/sandboxes/s/containers/c/stdin", r -> MockDaemon.json(200, Map.of("ok", true)));
    eq(true, k.writeStdin("s", "c", "hi\n".getBytes(StandardCharsets.UTF_8), null).get("ok"), "first stdin");
    raises(Kosa8Exception.class, () -> k.writeStdin("s", "c", "again\n".getBytes(StandardCharsets.UTF_8), null));
  }

  public void testFixesCheckpointCollisionStepsId() {
    AtomicInteger calls = new AtomicInteger();
    d.on("POST", "/kosa8/v1/sandboxes/s/snapshot", r -> calls.incrementAndGet() == 1
        ? MockDaemon.json(409, Map.of("error", "snapshot already exists: s-ckpt-1"))
        : MockDaemon.json(201, Map.of("id", r.json().get("id"))));
    eq("s-ckpt-2", k.checkpoint("s", "a").checkpoint().id(), "stepped id");
    d.on("POST", "/kosa8/v1/sandboxes/t/snapshot", r -> MockDaemon.json(409, Map.of("error", "sandbox busy")));
    raises(Kosa8Exception.ConflictException.class, () -> k.checkpoint("t", ""));
  }

  public void testFixesTwoStoresOneFileAndRedactedToken() throws Exception {
    Path f = Files.createTempDirectory("k8j").resolve("checkpoints.json");
    FileCheckpointStore a = new FileCheckpointStore(f), b = new FileCheckpointStore(f);
    List<Throwable> errs = Collections.synchronizedList(new ArrayList<>());
    List<Thread> ts = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      FileCheckpointStore s = i % 2 == 0 ? a : b;
      Thread t = new Thread(() -> {
        try {
          for (int j = 0; j < 20; j++) s.update(m -> null);
        } catch (Throwable e) {
          errs.add(e);
        }
      });
      ts.add(t);
      t.start();
    }
    for (Thread t : ts) t.join();
    check(errs.isEmpty(), "two stores on one file: " + errs);
    Endpoint e = Endpoint.resolve("https://w.example:443", null, "sekret", false, x -> null);
    check(!e.toString().contains("sekret"), "token redacted: " + e);
  }

  public static void main(String[] args) throws Exception {
    int pass = 0;
    List<String> failed = new ArrayList<>();
    for (Method m : ClientTest.class.getDeclaredMethods()) {
      if (!m.getName().startsWith("test")) continue;
      ClientTest t = new ClientTest();
      try (MockDaemon d = new MockDaemon()) {
        t.d = d;
        t.k = d.client();
        m.invoke(t);
        pass++;
        System.out.println("ok   " + m.getName());
      } catch (java.lang.reflect.InvocationTargetException e) {
        failed.add(m.getName());
        System.out.println("FAIL " + m.getName() + ": " + e.getCause());
        e.getCause().printStackTrace(System.out);
      }
    }
    System.out.println(pass + " passed, " + failed.size() + " failed");
    if (!failed.isEmpty()) System.exit(1);
  }
}
