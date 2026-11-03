package com.kosa8;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The toolbox: code, files, git, sessions, terminals, previews, lifecycle, templates. */
public final class ToolboxTest extends ClientTest {
  static final String S = "/kosa8/v1/sandboxes/sb1";

  public void testRunCodeDecodesArtifacts() {
    byte[] png = "\u0089PNG fake".getBytes(StandardCharsets.ISO_8859_1);
    d.on("POST", S + "/code", r -> MockDaemon.json(200, Map.of("language", "python", "image", "python:3.12-slim", "exit", 0,
        "stdout", "hi\n", "stderr", "", "duration_ms", 12,
        "artifacts", List.of(Map.of("type", "image/png", "name", "figure-1.png", "data", Base64.getEncoder().encodeToString(png))))));
    Results.CodeResult r = k.sandbox("sb1").runCode("print('hi')", new Options.Code().env(Map.of("A", "1")).timeout(Duration.ofSeconds(10)));
    check(r.ok(), "ok");
    eq("hi\n", r.stdout(), "stdout");
    eq(12L, r.durationMs(), "duration");
    eq("figure-1.png", r.artifacts().get(0).name(), "artifact name");
    check(java.util.Arrays.equals(png, r.artifacts().get(0).data()), "artifact bytes decoded");
    Map<String, Object> b = d.last().json();
    eq("python", b.get("language"), "default language");
    eq("print('hi')", b.get("code"), "code");
    eq(Map.of("A", "1"), b.get("env"), "env");
    eq(10L, b.get("timeout_s"), "timeout_s");
    check(!b.containsKey("image"), "no image");
  }

  public void testRunCodeTimedOutIsNotOk() {
    d.on("POST", S + "/code", r -> MockDaemon.json(200, Map.of("exit", 124, "stdout", "", "stderr", "killed", "timed_out", true)));
    Results.CodeResult r = k.runCode("sb1", "while True: pass", new Options.Code().language("javascript"));
    check(!r.ok(), "not ok");
    check(r.timedOut(), "timed out");
    eq("javascript", d.last().json().get("language"), "language");
  }

  public void testFilesRoutes() {
    d.on("GET", S + "/files", r -> MockDaemon.json(200, Map.of("path", "/workspace", "files",
        List.of(Map.of("name", "src", "path", "/workspace/src", "type", "dir", "size", 0, "mode", 493, "mod_time", "t"),
            Map.of("name", "a.txt", "path", "/workspace/a.txt", "type", "file", "size", 3, "mode", 420, "mod_time", "t")))));
    d.on("GET", S + "/files/stat", r -> MockDaemon.json(200, Map.of("name", "l", "path", "/workspace/l", "type", "symlink", "link", "a.txt")));
    d.on("GET", S + "/files/content", r -> MockDaemon.bytes("hello".getBytes(StandardCharsets.UTF_8), 5));
    d.on("PUT", S + "/files/content", r -> MockDaemon.json(200, Map.of("path", "/workspace/b.txt", "size", r.body().length)));
    d.on("DELETE", S + "/files", r -> MockDaemon.json(200, Map.of("removed", "/workspace/d")));
    d.on("POST", S + "/files/mkdir", r -> MockDaemon.json(200, Map.of("path", "/workspace/x/y")));
    d.on("POST", S + "/files/move", r -> MockDaemon.json(200, Map.of("path", "/workspace/c.txt")));

    List<Results.FileEntry> ls = k.listFiles("sb1");
    check(ls.get(0).isDir(), "dir");
    eq(3L, ls.get(1).size(), "size");
    eq(420, ls.get(1).mode(), "mode");
    check(d.last().rawTarget().contains("path=%2Fworkspace"), d.last().rawTarget());
    Results.FileEntry st = k.statFile("sb1", "l");
    eq("symlink", st.type(), "stat type");
    eq("a.txt", st.link(), "link");
    eq("hello", k.readText("sb1", "a.txt"), "read");
    eq(6L, k.writeFile("sb1", "b.txt", "héllo", new Options.WriteFile().mode("0644")).get("size"), "write size");
    eq("héllo", d.last().bodyText(), "body");
    eq("application/octet-stream", d.last().headers().get("content-type"), "content type");
    check(d.last().rawTarget().contains("mode=0644"), d.last().rawTarget());
    k.writeFile("sb1", "big.bin", new java.io.ByteArrayInputStream(new byte[3]), null);
    eq("chunked", d.last().headers().get("transfer-encoding"), "stream write is chunked");
    k.deleteFile("sb1", "d", new Options.DeleteFile().recursive(true));
    check(d.last().rawTarget().contains("recursive=1"), d.last().rawTarget());
    k.deleteFile("sb1", "e");
    check(!d.last().rawTarget().contains("recursive"), d.last().rawTarget());
    k.makeDir("sb1", "x/y");
    eq(Map.of("path", "x/y"), d.last().json(), "mkdir body");
    k.sandbox("sb1").moveFile("b.txt", "c.txt");
    eq(Map.of("from", "b.txt", "to", "c.txt"), d.last().json(), "move body");
  }

  public void testReadFileErrors() {
    d.on("GET", S + "/files/content", r -> MockDaemon.json(404, Map.of("error", "no such file: nope", "remediation", "")));
    raises(Kosa8Exception.NotFoundException.class, () -> k.readFile("sb1", "nope"));
    d.on("GET", "/kosa8/v1/sandboxes/sb2/files/content", r -> MockDaemon.bytes("abc".getBytes(StandardCharsets.UTF_8), 10));
    var e = raises(Kosa8Exception.StreamException.class, () -> k.readFile("sb2", "x"));
    check(e.getMessage().contains("got 3 of 10"), e.getMessage());
  }

  @SuppressWarnings("unchecked")
  public void testGit() {
    List<String> ops = new ArrayList<>();
    d.on("POST", S + "/git", r -> {
      String op = (String) r.json().get("op");
      ops.add(op);
      Map<String, Object> m = new LinkedHashMap<>(Map.of("op", op, "ok", true, "exit", 0, "stdout", "", "stderr", "", "path", "/workspace/repo"));
      if (op.equals("status")) m.put("status", Map.of("branch", "main", "clean", false, "files", List.of(Map.of("path", "a", "index", "M", "worktree", " "))));
      return MockDaemon.json(200, m);
    });
    Results.GitResult r = k.gitCommit("sb1", "repo", "msg");
    check(r.ok(), "ok");
    eq(List.of("add", "commit"), ops, "add then commit");
    eq(Map.of("op", "commit", "path", "repo", "message", "msg"), d.last().json(), "commit body");
    ops.clear();
    k.gitCommit("sb1", "repo", "msg", new Options.Git().addAll(false).authorName("A"));
    eq(List.of("commit"), ops, "no add");
    eq("A", d.last().json().get("author_name"), "author");
    k.gitClone("sb1", "https://github.com/x/y", null, new Options.Git().depth(1));
    Map<String, Object> b = d.last().json();
    eq("clone", b.get("op"), "op");
    eq(1L, b.get("depth"), "depth");
    check(!b.containsKey("path"), "null path omitted");
    Results.GitResult st = k.sandbox("sb1").gitStatus("repo");
    eq("main", st.status().get("branch"), "status branch");
    eq(1, ((List<Object>) st.status().get("files")).size(), "status files");
    check(st.log() == null, "no log");

    d.on("POST", "/kosa8/v1/sandboxes/sb2/git", q -> {
      ops.add((String) q.json().get("op"));
      return MockDaemon.json(200, Map.of("op", "add", "ok", false, "exit", 128, "stdout", "", "stderr", "not a git repository"));
    });
    ops.clear();
    Results.GitResult bad = k.gitCommit("sb2", "nope", "m");
    check(!bad.ok(), "failed add returned");
    eq(128, bad.exitCode(), "exit");
    eq(List.of("add"), ops, "commit not attempted");
  }

  public void testShellMakesSessionOnFirstUse() {
    List<Object> made = new ArrayList<>();
    d.on("POST", S + "/sessions/main/exec", r -> made.isEmpty()
        ? MockDaemon.json(404, Map.of("error", "no such session: main", "remediation", "create it with POST …/sessions"))
        : MockDaemon.json(200, Map.of("exit", 0, "stdout", "/tmp\n", "stderr", "", "cwd", "/tmp", "duration_ms", 4)));
    d.on("POST", S + "/sessions", r -> {
      made.add(r.json());
      return MockDaemon.json(200, Map.of("name", "main", "id", "session-main", "image", "alpine", "status", "running"));
    });
    Results.ShellResult r = k.shell("sb1", "cd /tmp && pwd", new Options.Shell().timeout(Duration.ofSeconds(5)));
    eq("/tmp", r.cwd(), "cwd");
    check(r.ok(), "ok");
    eq(List.of(Map.of("name", "main")), made, "session made");
    eq(5L, d.last().json().get("timeout_s"), "timeout_s");
    d.on("POST", "/kosa8/v1/sandboxes/sb2/sessions/main/exec", x -> MockDaemon.json(404, Map.of("error", "no such sandbox: sb2")));
    raises(Kosa8Exception.NotFoundException.class, () -> k.shell("sb2", "ls"));
  }

  public void testSessions() {
    d.on("POST", S + "/sessions", r -> MockDaemon.json(200, Map.of("name", "w", "id", "session-w", "image", "python:3.12", "status", "running")));
    d.on("GET", S + "/sessions", r -> MockDaemon.json(200, List.of(Map.of("name", "w", "id", "session-w", "status", "running"))));
    d.on("POST", S + "/sessions/w/exec", r -> MockDaemon.json(200, Map.of("exit", 2, "stdout", "", "stderr", "x", "truncated", true)));
    d.on("DELETE", S + "/sessions/w", r -> MockDaemon.json(200, Map.of("deleted", "w")));
    Results.Session s = k.createSession("sb1", "w", new Options.Session().image("python:3.12").env(Map.of("K", "v")));
    eq("session-w", s.id(), "id");
    eq(Map.of("name", "w", "image", "python:3.12", "env", Map.of("K", "v")), d.last().json(), "create body");
    eq("w", k.listSessions("sb1").get(0).name(), "list");
    Results.ShellResult r = k.sessionExec("sb1", "w", "false");
    eq(2, r.exitCode(), "exit");
    check(r.truncated(), "truncated");
    eq(Map.of("command", "false"), d.last().json(), "exec body");
    eq("w", k.deleteSession("sb1", "w").get("deleted"), "delete");
  }

  public void testPtyAndPreviews() {
    d.on("POST", S + "/pty", r -> MockDaemon.json(200, Map.of("id", "pty-1", "image", "alpine", "resized", true)));
    d.on("POST", S + "/containers/pty-1/resize", r -> MockDaemon.json(200, Map.of("ok", true)));
    d.on("POST", S + "/previews", r -> MockDaemon.json(200, Map.of("url", "http://h.preview/?t=x", "token", "x",
        "expires_at", "2026-11-03T13:00:00Z", "container", "session-main", "port", 8000, "listen", "127.0.0.1:7071")));
    d.on("POST", "/kosa8/v1/previews/rotate", r -> MockDaemon.json(200, Map.of("ok", true, "revoked", "every preview link")));
    eq("pty-1", k.startPty("sb1", new Options.Pty().command(List.of("bash")).size(120, 40)), "pty id");
    eq(Map.of("command", List.of("bash"), "cols", 120L, "rows", 40L), d.last().json(), "pty body");
    k.startPty("sb1");
    eq(Map.of(), d.last().json(), "bare pty body");
    eq(true, k.resizePty("sb1", "pty-1", 80, 24).get("ok"), "resize");
    eq(Map.of("cols", 80L, "rows", 24L), d.last().json(), "resize body");
    Results.Preview p = k.createPreview("sb1", 8000, new Options.Preview().session("main").expiresIn(Duration.ofMinutes(10)));
    eq("http://h.preview/?t=x", p.url(), "url");
    eq(8000, p.port(), "port");
    eq(Map.of("port", 8000L, "session", "main", "expires_in_s", 600L), d.last().json(), "preview body");
    eq(true, k.revokePreviews().get("ok"), "revoke");
  }

  public void testLifecycle() {
    d.on("POST", "/kosa8/v1/sandboxes", r -> MockDaemon.json(201, Map.of("id", "x")));
    k.createSandbox(new Options.Create().name("x").autoStopMin(15).ttlMin(60).ephemeral(true).template("py"));
    Map<String, Object> b = d.last().json();
    eq(15L, b.get("auto_stop_min"), "auto stop");
    eq(60L, b.get("ttl_min"), "ttl");
    eq(true, b.get("ephemeral"), "ephemeral");
    eq("py", b.get("template"), "template");
    check(!b.containsKey("auto_delete_min"), "unset omitted");
    k.createSandbox(new Options.Create().name("y"));
    check(!d.last().json().containsKey("ephemeral"), "ephemeral omitted by default");
    d.on("POST", S + "/lifecycle", r -> MockDaemon.json(200, Map.of("id", "sb1", "lifecycle", Map.of())));
    k.sandbox("sb1").setLifecycle(new Options.Lifecycle().autoStopMin(0));
    eq(Map.of("auto_stop_min", 0L), d.last().json(), "only given fields, 0 kept");
    k.setLifecycle("sb1", new Options.Lifecycle().ttlMin(30).ephemeral(false));
    eq(Map.of("ttl_min", 30L, "ephemeral", false), d.last().json(), "ttl and ephemeral");
  }

  public void testTemplatesListDelete() {
    d.on("GET", "/kosa8/v1/templates", r -> MockDaemon.json(200, List.of(Map.of("name", "py", "image", "docker.io/kosa8-template/py:latest",
        "size_bytes", 99, "created", "2026-11-03T12:35:48Z", "dockerfile", "Dockerfile"))));
    d.on("DELETE", "/kosa8/v1/templates/py", r -> MockDaemon.json(200, Map.of("deleted", "py")));
    Results.Template t = k.listTemplates().get(0);
    eq("py", t.name(), "name");
    eq(99L, t.sizeBytes(), "size");
    eq("py", k.deleteTemplate("py").get("deleted"), "delete");
  }

  /** Reads a tar the way Go's archive/tar does for what Tar writes: ustar prefix and PAX path. */
  static Map<String, byte[]> untar(byte[] b) {
    Map<String, byte[]> out = new LinkedHashMap<>();
    int off = 0;
    String paxPath = null;
    while (off + 512 <= b.length) {
      boolean zero = true;
      for (int i = off; i < off + 512; i++) if (b[i] != 0) { zero = false; break; }
      if (zero) break;
      long sum = 0;
      for (int i = off; i < off + 512; i++) sum += (i >= off + 148 && i < off + 156) ? ' ' : (b[i] & 0xff);
      long want = Long.parseLong(field(b, off + 148, 8).strip(), 8);
      if (sum != want) throw new AssertionError("bad checksum at " + off);
      String name = field(b, off, 100);
      String prefix = field(b, off + 345, 155);
      if (!prefix.isEmpty()) name = prefix + "/" + name;
      long size = Long.parseLong(field(b, off + 124, 12).strip(), 8);
      char type = (char) b[off + 156];
      String link = field(b, off + 157, 100);
      byte[] data = java.util.Arrays.copyOfRange(b, off + 512, off + 512 + (int) size);
      off += 512 + (int) ((size + 511) / 512 * 512);
      if (type == 'x') {
        String recs = new String(data, StandardCharsets.UTF_8);
        for (String rec : recs.split("\n")) {
          int sp = rec.indexOf(' ');
          eq(Integer.parseInt(rec.substring(0, sp)), (rec + "\n").getBytes(StandardCharsets.UTF_8).length, "pax record length");
          String kv = rec.substring(sp + 1);
          if (kv.startsWith("path=")) paxPath = kv.substring(5);
        }
        continue;
      }
      if (paxPath != null) name = paxPath;
      paxPath = null;
      out.put(type == '2' ? name + " -> " + link : name, data);
    }
    return out;
  }

  static String field(byte[] b, int off, int len) {
    int end = off;
    while (end < off + len && b[end] != 0) end++;
    return new String(b, off, end - off, StandardCharsets.UTF_8);
  }

  public void testBuildTemplateStreamsAndHonoursDockerignore() throws Exception {
    Path ctx = Files.createTempDirectory("k8jctx");
    Files.writeString(ctx.resolve("Dockerfile"), "FROM alpine\n");
    Files.writeString(ctx.resolve(".dockerignore"), "# comment\nsecret\n./build/\n");
    Files.createDirectories(ctx.resolve("secret"));
    Files.writeString(ctx.resolve("secret/k"), "x");
    Files.createDirectories(ctx.resolve("build"));
    Files.writeString(ctx.resolve("build/out"), "x");
    Files.writeString(ctx.resolve("secretive.txt"), "kept: only the folder secret is ignored");
    Files.writeString(ctx.resolve("app.py"), "print(1)");
    Files.setPosixFilePermissions(ctx.resolve("app.py"), PosixFilePermissions.fromString("rwxr-xr-x"));
    String deep = "d".repeat(60) + "/" + "e".repeat(60) + "/" + "f".repeat(90) + ".txt"; // 213 bytes: ustar prefix split
    Files.createDirectories(ctx.resolve(deep).getParent());
    Files.writeString(ctx.resolve(deep), "deep");
    String longLeaf = "g".repeat(150) + ".txt"; // one component past 100 bytes: needs PAX
    Files.writeString(ctx.resolve(longLeaf), "long");
    Files.createSymbolicLink(ctx.resolve("link"), Path.of("app.py"));

    d.on("POST", "/kosa8/v1/templates", r -> MockDaemon.frames(MockDaemon.frame("status", "step 1\n"),
        MockDaemon.frame("stdout", "built\n"), MockDaemon.done("ok", true, "image", "kosa8-template/py:latest")));
    ByteArrayOutputStream seen = new ByteArrayOutputStream();
    Map<String, Object> out = k.buildTemplate("py", ctx, new Options.Build().buildArgs(Map.of("V", "1")).egress("allow:pypi.org")
        .replace(true).onOutput(seen::writeBytes));
    eq("kosa8-template/py:latest", out.get("image"), "final frame");
    eq("step 1\nbuilt\n", seen.toString(StandardCharsets.UTF_8), "output");
    String q = d.last().rawTarget();
    for (String want : List.of("name=py", "dockerfile=Dockerfile", "buildarg=V%3D1", "egress=allow%3Apypi.org", "replace=1"))
      check(q.contains(want), want + " in " + q);
    Map<String, byte[]> names = untar(d.last().body());
    check(names.containsKey("app.py"), "app.py: " + names.keySet());
    check(names.containsKey("Dockerfile"), "Dockerfile");
    check(names.containsKey("secretive.txt"), "prefix match is by path component");
    check(names.keySet().stream().noneMatch(n -> n.startsWith("secret/") || n.equals("secret/") || n.startsWith("build")), "ignored: " + names.keySet());
    eq("deep", new String(names.get(deep), StandardCharsets.UTF_8), "ustar prefix split");
    eq("long", new String(names.get(longLeaf), StandardCharsets.UTF_8), "PAX long name");
    check(names.containsKey("link -> app.py"), "symlink kept as a link: " + names.keySet());
    check(names.containsKey("d".repeat(60) + "/"), "folders are entries");
  }

  public void testBuildTemplateErrors() throws Exception {
    Path ctx = Files.createTempDirectory("k8jctx");
    raises(Kosa8Exception.class, () -> k.buildTemplate("py", ctx));
    Files.writeString(ctx.resolve("Dockerfile"), "FROM alpine\n");
    d.on("POST", "/kosa8/v1/templates", r -> MockDaemon.frames(MockDaemon.frame("status", "step 1\n"), MockDaemon.done("error", "RUN failed")));
    var e = raises(Kosa8Exception.StreamException.class, () -> k.buildTemplate("py", ctx));
    eq("RUN failed", e.getMessage(), "message");
    d.on("POST", "/kosa8/v1/templates", r -> MockDaemon.json(409, Map.of("error", "template py already exists", "remediation", "add replace=1")));
    raises(Kosa8Exception.ConflictException.class, () -> k.buildTemplate("py", ctx));
    d.on("POST", "/kosa8/v1/templates", r -> MockDaemon.frames(MockDaemon.frame("status", "x")));
    raises(Kosa8Exception.StreamException.class, () -> k.buildTemplate("py", ctx));
  }

  public void testPaxRecordLength() {
    for (int n : new int[] {1, 85, 86, 87, 990, 995, 996, 997, 5000}) {
      String r = Tar.Writer.record("path", "x".repeat(n));
      int sp = r.indexOf(' ');
      eq(r.getBytes(StandardCharsets.UTF_8).length, Integer.parseInt(r.substring(0, sp)), "record length for " + n);
    }
  }
}
