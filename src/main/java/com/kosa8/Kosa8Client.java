package com.kosa8;

import com.kosa8.Transport.Request;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Typed client for the kosa8 native API (/kosa8/v1) over the daemon's unix socket, or a remote
 * worker via KOSA8_HOST / KOSA8_TOKEN. Method for method it matches @kosa8/sdk (TypeScript),
 * kosa8 (Python), kosa8-go and the Ruby gem.
 *
 * <pre>{@code
 * Kosa8Client k = Kosa8Client.builder().build();
 * k.createSandbox(new Options.Create().name("demo").egress("deny"));
 * k.pullIntoSandbox("demo", "alpine:latest");
 * System.out.print(k.run("demo", "alpine:latest", List.of("echo", "hi")).stdout());
 * }</pre>
 */
public final class Kosa8Client {
  public static final String VERSION = "0.2.0";
  private static final Pattern TRANSIENT = Pattern.compile(
      "connection refused|EOF|connection reset|broken pipe|no such file or directory|stream ended|ECONNREFUSED|ECONNRESET|unreachable",
      Pattern.CASE_INSENSITIVE);

  private final Transport t;
  private final CheckpointBook ckpts;
  private final java.util.Set<String> stdinSent = java.util.concurrent.ConcurrentHashMap.newKeySet();

  private Kosa8Client(Transport t, CheckpointStore store) {
    this.t = t;
    this.ckpts = new CheckpointBook(store);
  }

  public static Builder builder() { return new Builder(); }

  /** Configures a client. The defaults talk to the local daemon. */
  public static final class Builder {
    private String socketPath;
    private String host;
    private String token;
    private boolean insecure;
    private String actor = "tool";
    private String agentToken;
    private Map<String, String> headers = Map.of();
    private Duration timeout;
    private CheckpointStore checkpoints;
    private Function<String, String> getenv = System::getenv;
    private Path workerFile = Endpoint.defaultWorkerFile();

    /** Default ~/.kosa8/run/kosa8.sock. */
    public Builder socketPath(String v) { socketPath = v; return this; }
    /** A remote worker; default $KOSA8_HOST. */
    public Builder host(String v) { host = v; return this; }
    /** Bearer token for the host; default $KOSA8_TOKEN when the host came from the env. */
    public Builder token(String v) { token = v; return this; }
    /** Allow plain http to a non-loopback worker. */
    public Builder insecure(boolean v) { insecure = v; return this; }
    /** human | agent | tool (default tool). */
    public Builder actor(String v) { actor = v; return this; }
    /** Default $KOSA8_AGENT_TOKEN. */
    public Builder agentToken(String v) { agentToken = v; return this; }
    public Builder headers(Map<String, String> v) { headers = v; return this; }
    /** Default timeout for every request; none by default. */
    public Builder timeout(Duration v) { timeout = v; return this; }
    /** Where checkpoint stacks live; default the CLI's own ~/.kosa8/checkpoints.json. */
    public Builder checkpoints(CheckpointStore v) { checkpoints = v; return this; }
    /**
     * The saved worker connection read when neither a host, KOSA8_HOST nor a socket path is set;
     * default ~/.kosa8/worker.json (written by {@code kosa8 wsl setup} and {@code kosa8 worker join}).
     * null reads none.
     */
    public Builder workerFile(Path v) { workerFile = v; return this; }
    /** For tests. */
    public Builder getenv(Function<String, String> v) { getenv = v; return this; }

    public Kosa8Client build() {
      Endpoint ep = Endpoint.resolve(host, socketPath, token, insecure, getenv, workerFile);
      String agent = agentToken != null ? agentToken : getenv.apply("KOSA8_AGENT_TOKEN");
      return new Kosa8Client(new Transport(ep, actor, agent, headers, timeout),
          checkpoints != null ? checkpoints : new FileCheckpointStore());
    }
  }

  /** Where this client sends requests. */
  public Endpoint endpoint() { return t.endpoint; }

  private static String sb(String id, String... rest) {
    StringBuilder p = new StringBuilder("/kosa8/v1/sandboxes/").append(Transport.enc(id));
    for (String r : rest) p.append('/').append(r);
    return p.toString();
  }

  private static String appr(Options.Call<?> c) { return c == null ? null : c.approval; }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> obj(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> objs(Object o) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (o instanceof List<?> l) for (Object x : l) if (x instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
    return out;
  }

  // --- daemon -------------------------------------------------------------------

  /** GET /kosa8/v1/health: daemon status and version. */
  public Map<String, Object> health() { return obj(t.json(new Request("GET", "/kosa8/v1/health"))); }

  /** The daemon's version. */
  public String version() { return String.valueOf(health().get("version")); }

  /** POST /kosa8/v1/pull: pull an image into the host engine. */
  public Map<String, Object> pull(String ref, Options.Approval o) {
    return obj(t.json(new Request("POST", "/kosa8/v1/pull").body(Map.of("ref", ref)).approval(appr(o))));
  }

  public Map<String, Object> pull(String ref) { return pull(ref, null); }

  // --- sandboxes ----------------------------------------------------------------

  /** GET /kosa8/v1/sandboxes */
  public List<Map<String, Object>> listSandboxes() { return objs(t.json(new Request("GET", "/kosa8/v1/sandboxes"))); }

  /** By id, then by name; null when there is none. */
  public Map<String, Object> getSandbox(String idOrName) {
    List<Map<String, Object>> l = listSandboxes();
    for (String key : List.of("id", "name"))
      for (Map<String, Object> s : l) if (idOrName.equals(s.get(key))) return s;
    return null;
  }

  /** POST /kosa8/v1/sandboxes: boot a microVM sandbox. */
  public Map<String, Object> createSandbox(Options.Create o) {
    if (o == null) o = new Options.Create();
    return obj(t.json(new Request("POST", "/kosa8/v1/sandboxes").body(o.body()).approval(o.approval)));
  }

  /** POST …/up: boot a stopped sandbox from its own disk. */
  public Map<String, Object> startSandbox(String sandbox, Options.Approval o) {
    return obj(t.json(new Request("POST", sb(sandbox, "up")).approval(appr(o))));
  }

  public Map<String, Object> startSandbox(String sandbox) { return startSandbox(sandbox, null); }

  /** POST …/down: stop without deleting (the disk is kept). */
  public Map<String, Object> stopSandbox(String sandbox, Options.Approval o) {
    return obj(t.json(new Request("POST", sb(sandbox, "down")).approval(appr(o))));
  }

  public Map<String, Object> stopSandbox(String sandbox) { return stopSandbox(sandbox, null); }

  /**
   * DELETE /kosa8/v1/sandboxes/{id}. Its checkpoints (and their snapshots) go too unless
   * keepCheckpoints; the answer carries checkpoints_dropped.
   */
  public Map<String, Object> deleteSandbox(String sandbox, Options.Delete o) {
    if (o == null) o = new Options.Delete();
    Map<String, Object> out = new LinkedHashMap<>(obj(t.json(new Request("DELETE", sb(sandbox)).query("wipe", o.wipe).approval(o.approval))));
    int dropped = 0;
    if (!o.keepCheckpoints) {
      try {
        List<String> ids = ckpts.forget(sandbox);
        for (String id : ids) {
          try {
            deleteSnapshot(id, new Options.SnapshotDelete().wipe(o.wipe).approval(o.approval));
          } catch (Kosa8Exception ignored) {
            // the count reports what was forgotten
          }
        }
        dropped = ids.size();
      } catch (Kosa8Exception ignored) {
        // bookkeeping debris, not a failed delete
      }
    }
    out.put("checkpoints_dropped", dropped);
    return out;
  }

  public Map<String, Object> deleteSandbox(String sandbox) { return deleteSandbox(sandbox, null); }

  /** POST …/pull: pull an image into the sandbox's private engine. */
  public Map<String, Object> pullIntoSandbox(String sandbox, String ref, Options.Approval o) {
    return obj(t.json(new Request("POST", sb(sandbox, "pull")).body(Map.of("ref", ref)).approval(appr(o))));
  }

  public Map<String, Object> pullIntoSandbox(String sandbox, String ref) { return pullIntoSandbox(sandbox, ref, null); }

  /** GET …/images */
  public List<String> sandboxImages(String sandbox) {
    List<String> out = new ArrayList<>();
    if (t.json(new Request("GET", sb(sandbox, "images"))) instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x));
    return out;
  }

  /** GET …/containers */
  public List<Map<String, Object>> sandboxContainers(String sandbox) {
    return objs(t.json(new Request("GET", sb(sandbox, "containers"))));
  }

  /** GET …/egress: the policy the host enforces and what it decided. */
  public Map<String, Object> sandboxEgress(String sandbox) { return obj(t.json(new Request("GET", sb(sandbox, "egress")))); }

  // --- processes ----------------------------------------------------------------

  /** POST …/run: run a container to completion. The image must already be in the sandbox. */
  public Results.Exec run(String sandbox, String image, List<String> args, Options.Run o) {
    if (o == null) o = new Options.Run();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("image", image);
    body.put("args", args == null ? List.of() : args);
    if (o.env != null) body.put("env", o.env);
    return collect(new Request("POST", sb(sandbox, "run")).body(body).approval(o.approval).timeout(o.timeout), o);
  }

  public Results.Exec run(String sandbox, String image, List<String> args) { return run(sandbox, image, args, null); }

  /** POST …/start: start a detached container; returns its id. */
  public String startContainer(String sandbox, String image, List<String> args, Options.Approval o) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("image", image);
    body.put("args", args == null ? List.of() : args);
    return String.valueOf(obj(t.json(new Request("POST", sb(sandbox, "start")).body(body).approval(appr(o)))).get("id"));
  }

  public String startContainer(String sandbox, String image, List<String> args) { return startContainer(sandbox, image, args, null); }

  private Request execReq(String sandbox, String container, List<String> args) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("container", container);
    body.put("args", args == null ? List.of() : args);
    return new Request("POST", sb(sandbox, "exec")).body(body);
  }

  /** POST …/exec: run a command in a running container of the sandbox. */
  public Results.Exec exec(String sandbox, String container, List<String> args, Options.Run o) {
    if (o == null) o = new Options.Run();
    if (o.env != null && !o.env.isEmpty())
      // The daemon's exec takes no environment; dropping it silently would run
      // the command without the variables the caller asked for.
      throw new Kosa8Exception("exec does not take env: the container keeps the environment it was started with. "
          + "Set it at startInteractive, or wrap the command: List.of(\"env\", \"K=V\", \"cmd\")");
    return collect(execReq(sandbox, container, args).approval(o.approval).timeout(o.timeout), o);
  }

  public Results.Exec exec(String sandbox, String container, List<String> args) { return exec(sandbox, container, args, null); }

  /** The raw frame stream of an exec, for callers that process output as it arrives. */
  public void execFrames(String sandbox, String container, List<String> args, Consumer<Frame> fn, Options.Approval o) {
    t.frames(execReq(sandbox, container, args).approval(appr(o)), fn);
  }

  /** POST …/start-interactive: start a container with stdin held open; returns its id. */
  public String startInteractive(String sandbox, String image, List<String> args, Options.Interactive o) {
    if (o == null) o = new Options.Interactive();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("image", image);
    body.put("args", args == null ? List.of() : args);
    body.put("env", o.env);
    body.put("tty", o.tty);
    return String.valueOf(obj(t.json(new Request("POST", sb(sandbox, "start-interactive")).body(body).approval(o.approval))).get("id"));
  }

  /** POST …/containers/{cid}/stdin: stream bytes into the container's stdin (sent chunked). */
  public Map<String, Object> writeStdin(String sandbox, String container, InputStream data, Options.Approval o) {
    stdinOnce(sandbox, container);
    return obj(t.json(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "stdin")).body(data).approval(appr(o))));
  }

  /** POST …/containers/{cid}/stdin with a fixed body. */
  public Map<String, Object> writeStdin(String sandbox, String container, byte[] data, Options.Approval o) {
    stdinOnce(sandbox, container);
    return obj(t.json(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "stdin")).body(data).approval(appr(o))));
  }

  /**
   * The request body is the container's whole stdin: the daemon closes stdin when it
   * ends, so writeStdin can be called once per container. Pass an InputStream that
   * yields over time (a PipedInputStream) to feed input as you go.
   */
  private void stdinOnce(String sandbox, String container) {
    if (!stdinSent.add(sandbox + "\u0000" + container))
      throw new Kosa8Exception("stdin of container " + container + " in " + sandbox + " was already sent and is closed: "
          + "the daemon treats one writeStdin body as the whole of stdin. Pass an InputStream to stream input");
  }

  /** POST …/containers/{cid}/attach: follow a container's output until it exits. */
  public Results.Exec attach(String sandbox, String container, Options.Run o) {
    if (o == null) o = new Options.Run();
    return collect(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "attach")).approval(o.approval).timeout(o.timeout), o);
  }

  private Results.Exec collect(Request r, Options.Run o) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    Results.Exec[] result = new Results.Exec[1];
    try {
      t.frames(r, f -> {
        if (result[0] != null) return;
        if (f.done()) {
          if (f.error() != null)
            throw new Kosa8Exception.StreamException(f.error(), out.toString(java.nio.charset.StandardCharsets.UTF_8),
                err.toString(java.nio.charset.StandardCharsets.UTF_8), f.error());
          result[0] = new Results.Exec(f.exit() != null ? f.exit() : 0, out.toByteArray(), err.toByteArray());
          return;
        }
        byte[] d = f.data();
        if (d.length == 0) return;
        String s = f.stream() == null ? "stdout" : f.stream();
        switch (s) {
          case "stderr" -> {
            err.writeBytes(d);
            if (o.onStderr != null) o.onStderr.accept(d);
          }
          case "status" -> {
            if (o.onStatus != null) o.onStatus.accept(d);
          }
          default -> {
            out.writeBytes(d);
            if (o.onStdout != null) o.onStdout.accept(d);
          }
        }
      });
    } catch (Kosa8Exception.StreamException e) {
      if (e.stdout().isEmpty() && e.stderr().isEmpty() && (out.size() > 0 || err.size() > 0))
        throw new Kosa8Exception.StreamException(e.getMessage(), out.toString(java.nio.charset.StandardCharsets.UTF_8),
            err.toString(java.nio.charset.StandardCharsets.UTF_8), e.daemonMessage());
      throw e;
    }
    if (result[0] == null)
      throw new Kosa8Exception.StreamException("stream ended unexpectedly (no final frame)",
          out.toString(java.nio.charset.StandardCharsets.UTF_8), err.toString(java.nio.charset.StandardCharsets.UTF_8), null);
    return result[0];
  }

  // --- snapshots and forks ------------------------------------------------------

  /** POST …/snapshot: capture memory, processes and disks. */
  public Map<String, Object> snapshot(String sandbox, Options.Snapshot o) {
    if (o == null) o = new Options.Snapshot();
    Map<String, Object> body = new LinkedHashMap<>();
    if (o.id != null) body.put("id", o.id);
    if (o.encrypt) body.put("encrypt", true);
    return obj(t.json(new Request("POST", sb(sandbox, "snapshot")).body(body).approval(o.approval)));
  }

  /** GET /kosa8/v1/snapshots */
  public List<Map<String, Object>> listSnapshots() { return objs(t.json(new Request("GET", "/kosa8/v1/snapshots"))); }

  /** POST /kosa8/v1/snapshots/{id}/restore: boot a sandbox from a snapshot, processes resumed. */
  public Map<String, Object> restoreSnapshot(String snapshotId, Options.Restore o) {
    if (o == null) o = new Options.Restore();
    Map<String, Object> body = new LinkedHashMap<>();
    if (o.name != null) body.put("name", o.name);
    if (o.label != null) body.put("label", o.label);
    if (o.allowTampered) body.put("allow_tampered", true);
    return obj(t.json(new Request("POST", "/kosa8/v1/snapshots/" + Transport.enc(snapshotId) + "/restore").body(body).approval(o.approval)));
  }

  /** DELETE /kosa8/v1/snapshots/{id} */
  public Map<String, Object> deleteSnapshot(String snapshotId, Options.SnapshotDelete o) {
    if (o == null) o = new Options.SnapshotDelete();
    return obj(t.json(new Request("DELETE", "/kosa8/v1/snapshots/" + Transport.enc(snapshotId))
        .query("wipe", o.wipe).query("force", o.force).approval(o.approval)));
  }

  /** POST …/fork: n live copies of a running sandbox. */
  public Results.Fork fork(String sandbox, int n, Options.Fork o) {
    if (o == null) o = new Options.Fork();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("n", Math.max(1, n));
    if (o.prefix != null) body.put("prefix", o.prefix);
    if (o.label != null) body.put("label", o.label);
    Map<String, Object> m = obj(t.json(new Request("POST", sb(sandbox, "fork")).body(body).approval(o.approval)));
    return new Results.Fork(objs(m.get("forks")), m.get("snapshot"),
        m.get("fork_ms") instanceof Number x ? x.longValue() : 0, m.get("count") instanceof Number c ? c.intValue() : 0);
  }

  // --- explore: best-of-N -------------------------------------------------------

  /**
   * Best-of-N, as {@code kosa8 explore}: forks the sandbox once per variant, runs each
   * ({@code sh -c VARIANT} in the image) concurrently in its own branch, keeps the fastest
   * successful branch and deletes the rest.
   */
  public Results.Explore explore(String sandbox, List<String> variants, Options.Explore o) {
    if (o == null) o = new Options.Explore();
    if (variants.size() < 2) throw new Kosa8Exception("give at least two variants; with one there is nothing to compare");
    String a = o.approval;
    if (o.pull) pullIntoSandbox(sandbox, o.image, Options.Approval.of(a));
    String prefix = o.prefix != null ? o.prefix : sandbox + "-explore-" + (System.currentTimeMillis() / 1000 % 100000);
    Results.Fork forked = fork(sandbox, variants.size(), new Options.Fork().prefix(prefix).approval(a));
    List<String> names = new ArrayList<>();
    for (Map<String, Object> f : forked.forks()) if (f.get("id") instanceof String id) names.add(id);
    if (names.size() < variants.size())
      throw new Kosa8Exception("forked " + names.size() + " branches for " + variants.size()
          + " variants; see listSandboxes() for what was created");
    ExecutorService pool = Executors.newFixedThreadPool(variants.size());
    List<Results.ExploreBranch> branches = new ArrayList<>();
    try {
      List<Future<Results.ExploreBranch>> fs = new ArrayList<>();
      final Options.Explore oo = o;
      for (int i = 0; i < variants.size(); i++) {
        String name = names.get(i);
        String v = variants.get(i);
        fs.add(pool.submit(() -> runBranch(name, oo.image, v, oo.timeout, a)));
      }
      for (Future<Results.ExploreBranch> f : fs) branches.add(f.get());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Kosa8Exception("explore interrupted", e);
    } catch (ExecutionException e) {
      throw new Kosa8Exception("explore branch failed: " + e.getCause(), e.getCause());
    } finally {
      pool.shutdownNow();
    }
    String winner = "";
    long best = Long.MAX_VALUE;
    for (Results.ExploreBranch b : branches) {
      if (b.error() == null && b.exit() == 0 && b.durationMs() < best) {
        winner = b.name();
        best = b.durationMs();
      }
    }
    if (o.keep != null) {
      if (!names.contains(o.keep))
        throw new Kosa8Exception("keep " + o.keep + " is not one of the branches (" + String.join(", ", names) + "); all of them were kept");
      winner = o.keep;
    }
    int removed = 0;
    if (!o.keepAll) {
      for (Results.ExploreBranch b : branches) {
        if (b.name().equals(winner)) continue;
        try {
          t.json(new Request("DELETE", sb(b.name())).approval(a));
          removed++;
        } catch (Kosa8Exception ignored) {
          // reported by the count
        }
      }
    }
    return new Results.Explore(sandbox, branches, winner, removed);
  }

  private Results.ExploreBranch runBranch(String name, String image, String variant, Duration timeout, String a) {
    long start = System.nanoTime();
    int exit = -1;
    String output = "";
    String error = null;
    // A freshly restored branch can refuse the first connection while its engine
    // settles; retry connection-level failures briefly, as the CLI does.
    for (int attempt = 0; attempt < 4; attempt++) {
      try {
        Results.Exec r = run(name, image, List.of("sh", "-c", variant), new Options.Run().timeout(timeout).approval(a));
        exit = r.exitCode();
        output = (r.stdout() + r.stderr()).replaceAll("\n+$", "");
        error = null;
        break;
      } catch (Kosa8Exception e) {
        error = e.getMessage();
        if (e instanceof Kosa8Exception.StreamException s) output = (s.stdout() + s.stderr()).replaceAll("\n+$", "");
        if (e instanceof Kosa8Exception.TimeoutException || !TRANSIENT.matcher(String.valueOf(error)).find()) break;
        try {
          Thread.sleep((attempt + 1) * 500L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          break;
        }
      }
    }
    return new Results.ExploreBranch(name, variant, exit, output, error, (System.nanoTime() - start) / 1_000_000);
  }

  // --- checkpoints: client-side over snapshots, as pkg/checkpoint ---------------

  /**
   * Save a restore point: a snapshot {@code <sandbox>-ckpt-<n>} on the sandbox's stack, in the
   * same store {@code kosa8 checkpoint} uses. Past 10 the oldest is evicted, once its
   * replacement exists.
   */
  public Results.CheckpointSaved checkpoint(String sandbox, String label, Options.Approval o) {
    CheckpointBook.Next next = ckpts.nextId(sandbox);
    // Two checkpoints taken at once (here or by the CLI) can pick the same next
    // id; the loser's snapshot is refused with 409. The winner records its id only
    // once its snapshot is done, so step past the taken id rather than re-reading.
    String id = next.id();
    Map<String, Object> snap;
    for (int attempt = 0; ; attempt++) {
      try {
        snap = snapshot(sandbox, new Options.Snapshot().id(id).approval(appr(o)));
        break;
      } catch (Kosa8Exception.ConflictException e) {
        String dm = e.daemonMessage() == null ? e.getMessage() : e.daemonMessage();
        if (attempt == 9 || dm == null || !dm.contains("already exists")) throw e;
        id = sandbox + "-ckpt-" + (CheckpointBook.seq(id, sandbox) + 1);
      }
    }
    next = new CheckpointBook.Next(id, next.evict());
    for (String old : next.evict()) {
      try {
        deleteSnapshot(old, new Options.SnapshotDelete().approval(appr(o)));
      } catch (Kosa8Exception ignored) {
        // best effort
      }
    }
    Checkpoint rec = ckpts.record(next.id(), sandbox, label, next.evict());
    return new Results.CheckpointSaved(rec, next.evict(), snap);
  }

  public Results.CheckpointSaved checkpoint(String sandbox, String label) { return checkpoint(sandbox, label, null); }

  /** A sandbox's restore points, oldest first. */
  public List<Checkpoint> listCheckpoints(String sandbox) { return ckpts.list(sandbox); }

  /**
   * Undo: put the sandbox back to a checkpoint ({@code to}: an id or label; null means the
   * latest) under the same name, processes resumed. Checkpoints taken after the target are
   * discarded. The current state is lost.
   */
  public Results.Rewind rewind(String sandbox, String to, Options.Approval o) {
    Checkpoint target = to != null ? ckpts.resolve(sandbox, to) : ckpts.latest(sandbox);
    // The sandbox must go first: the restored VM takes its name. A refusal here
    // leaves everything as it was, and is thrown as is.
    t.json(new Request("DELETE", sb(sandbox)).approval(appr(o)));
    Map<String, Object> restored;
    try {
      restored = restoreSnapshot(target.id(), new Options.Restore().name(sandbox).approval(appr(o)));
    } catch (Kosa8Exception e) {
      throw new Kosa8Exception.CheckpointException("restore failed and " + sandbox + " no longer exists (it was deleted to free its name for the "
          + "restore): " + e.getMessage() + " — the checkpoint is intact; recover with restoreSnapshot(\"" + target.id() + "\", new Options.Restore().name(\"" + sandbox + "\"))", e);
    }
    List<String> dropped = List.of();
    try {
      dropped = ckpts.pop(sandbox, target.id());
      for (String id : dropped) {
        try {
          deleteSnapshot(id, new Options.SnapshotDelete().approval(appr(o)));
        } catch (Kosa8Exception ignored) {
          // best effort
        }
      }
    } catch (Kosa8Exception ignored) {
      // bookkeeping
    }
    return new Results.Rewind(restored.get("sandbox"), target, dropped, restored.get("restore_ms"));
  }

  public Results.Rewind rewind(String sandbox, String to) { return rewind(sandbox, to, null); }

  // --- toolbox: code, files, git, sessions, terminals, previews, lifecycle, templates

  private static String str(Object o) { return o instanceof String s ? s : ""; }
  private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0; }
  private static boolean bool(Object o) { return Boolean.TRUE.equals(o); }

  /** A request timeout for a call whose run is limited to d: the run plus two minutes for the round trip. */
  private static Duration slack(Duration d) { return d == null ? null : d.plusMinutes(2); }

  private static double seconds(Duration d) { return d.toMillis() / 1000.0; }

  /**
   * POST …/code: run a Python, JavaScript or TypeScript snippet. It starts in /workspace;
   * charts a Python snippet draws with matplotlib come back in artifacts as PNG bytes.
   */
  public Results.CodeResult runCode(String sandbox, String code, Options.Code o) {
    if (o == null) o = new Options.Code();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("language", o.language);
    body.put("code", code);
    if (o.image != null && !o.image.isEmpty()) body.put("image", o.image);
    if (o.env != null && !o.env.isEmpty()) body.put("env", o.env);
    if (o.timeout != null && !o.timeout.isZero()) body.put("timeout_s", seconds(o.timeout));
    Map<String, Object> m = obj(t.json(new Request("POST", sb(sandbox, "code")).body(body).approval(o.approval).timeout(slack(o.timeout))));
    List<Results.Artifact> arts = new ArrayList<>();
    for (Map<String, Object> a : objs(m.get("artifacts")))
      arts.add(new Results.Artifact(str(a.get("type")), str(a.get("name")), java.util.Base64.getDecoder().decode(str(a.get("data")))));
    return new Results.CodeResult((int) num(m.get("exit")), str(m.get("stdout")), str(m.get("stderr")), str(m.get("language")),
        str(m.get("image")), arts, num(m.get("duration_ms")), bool(m.get("timed_out")), bool(m.get("truncated")));
  }

  public Results.CodeResult runCode(String sandbox, String code) { return runCode(sandbox, code, null); }

  private static Results.FileEntry entry(Map<String, Object> m) {
    String link = str(m.get("link"));
    return new Results.FileEntry(str(m.get("name")), str(m.get("path")), str(m.get("type")), num(m.get("size")),
        (int) num(m.get("mode")), str(m.get("mod_time")), link.isEmpty() ? null : link);
  }

  /** GET …/files: a folder in the workspace (or the one entry, for a file). */
  public List<Results.FileEntry> listFiles(String sandbox, String path) {
    Map<String, Object> m = obj(t.json(new Request("GET", sb(sandbox, "files")).query("path", path)));
    List<Results.FileEntry> out = new ArrayList<>();
    for (Map<String, Object> f : objs(m.get("files"))) out.add(entry(f));
    return out;
  }

  /** The workspace, /workspace. */
  public List<Results.FileEntry> listFiles(String sandbox) { return listFiles(sandbox, "/workspace"); }

  /** GET …/files/stat: one entry, not following a symlink. */
  public Results.FileEntry statFile(String sandbox, String path) {
    return entry(obj(t.json(new Request("GET", sb(sandbox, "files", "stat")).query("path", path))));
  }

  /** GET …/files/content: a file's bytes. A body cut short throws StreamException. */
  public byte[] readFile(String sandbox, String path) {
    Request r = new Request("GET", sb(sandbox, "files", "content")).query("path", path);
    try (Transport.Response resp = t.open(r)) {
      byte[] b;
      try {
        b = resp.readAll();
      } catch (java.io.IOException e) {
        if (resp.timedOut.get()) throw t.timeout(r);
        throw new Kosa8Exception.StreamException("read " + path + ": " + e.getMessage(), "", "", null);
      }
      if (resp.status >= 400) throw Kosa8Exception.fromResponse(resp.status, Transport.decode(b), r.method, r.path);
      String want = resp.headers.get("content-length");
      if (want != null && Long.parseLong(want) != b.length)
        throw new Kosa8Exception.StreamException(path + ": got " + b.length + " of " + want + " bytes", "", "", null);
      return b;
    }
  }

  /** A file as UTF-8 text. */
  public String readText(String sandbox, String path) {
    return new String(readFile(sandbox, path), java.nio.charset.StandardCharsets.UTF_8);
  }

  private Request writeReq(String sandbox, String path, Object body, Options.WriteFile o) {
    if (o == null) o = new Options.WriteFile();
    return new Request("PUT", sb(sandbox, "files", "content")).query("path", path).query("mode", o.mode).body(body).approval(o.approval);
  }

  /** PUT …/files/content: replace a file, making its folders. */
  public Map<String, Object> writeFile(String sandbox, String path, byte[] data, Options.WriteFile o) {
    return obj(t.json(writeReq(sandbox, path, data, o)));
  }

  public Map<String, Object> writeFile(String sandbox, String path, byte[] data) { return writeFile(sandbox, path, data, null); }

  /** Text, as UTF-8. */
  public Map<String, Object> writeFile(String sandbox, String path, String text, Options.WriteFile o) {
    return writeFile(sandbox, path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), o);
  }

  public Map<String, Object> writeFile(String sandbox, String path, String text) { return writeFile(sandbox, path, text, null); }

  /** A stream, sent chunked: for files too large to hold in memory. */
  public Map<String, Object> writeFile(String sandbox, String path, InputStream data, Options.WriteFile o) {
    return obj(t.json(writeReq(sandbox, path, data, o)));
  }

  /** DELETE …/files: a file, or with recursive a folder. */
  public Map<String, Object> deleteFile(String sandbox, String path, Options.DeleteFile o) {
    if (o == null) o = new Options.DeleteFile();
    return obj(t.json(new Request("DELETE", sb(sandbox, "files")).query("path", path).query("recursive", o.recursive).approval(o.approval)));
  }

  public Map<String, Object> deleteFile(String sandbox, String path) { return deleteFile(sandbox, path, null); }

  /** POST …/files/mkdir: a folder and its parents. */
  public Map<String, Object> makeDir(String sandbox, String path, Options.Approval o) {
    return obj(t.json(new Request("POST", sb(sandbox, "files", "mkdir")).body(Map.of("path", path)).approval(appr(o))));
  }

  public Map<String, Object> makeDir(String sandbox, String path) { return makeDir(sandbox, path, null); }

  /** POST …/files/move: rename or move a file or folder. */
  public Map<String, Object> moveFile(String sandbox, String from, String to, Options.Approval o) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("from", from);
    body.put("to", to);
    return obj(t.json(new Request("POST", sb(sandbox, "files", "move")).body(body).approval(appr(o))));
  }

  public Map<String, Object> moveFile(String sandbox, String from, String to) { return moveFile(sandbox, from, to, null); }

  /**
   * POST …/git: op is clone, status, add, commit, push, pull, checkout, branches, log or
   * diff; the options carry that op's fields. A git failure returns ok() == false.
   */
  public Results.GitResult git(String sandbox, String op, Options.Git o) {
    if (o == null) o = new Options.Git();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("op", op);
    body.putAll(o.fields);
    Map<String, Object> m = obj(t.json(new Request("POST", sb(sandbox, "git")).body(body).approval(o.approval)));
    return new Results.GitResult(m.get("op") instanceof String s ? s : op, bool(m.get("ok")), (int) num(m.get("exit")),
        str(m.get("stdout")), str(m.get("stderr")), str(m.get("path")),
        m.get("status") instanceof Map<?, ?> ? obj(m.get("status")) : null,
        m.get("log") instanceof List<?> ? objs(m.get("log")) : null,
        m.get("branches") instanceof List<?> ? objs(m.get("branches")) : null);
  }

  /** Clone url into path (null: the daemon picks a folder in /workspace). */
  public Results.GitResult gitClone(String sandbox, String url, String path, Options.Git o) {
    return git(sandbox, "clone", (o == null ? new Options.Git() : o.copy()).url(url).path(path));
  }

  public Results.GitResult gitClone(String sandbox, String url, String path) { return gitClone(sandbox, url, path, null); }

  /** The repository's branch, upstream and changed files, in status(). */
  public Results.GitResult gitStatus(String sandbox, String path) { return git(sandbox, "status", new Options.Git().path(path)); }

  /** Stage everything (unless addAll(false)) and commit. A failed add is returned without committing. */
  public Results.GitResult gitCommit(String sandbox, String path, String message, Options.Git o) {
    if (o == null) o = new Options.Git();
    if (o.addAll) {
      Options.Git add = new Options.Git().path(path).approval(o.approval);
      if (o.fields.get("image") instanceof String img) add.image(img);
      Results.GitResult r = git(sandbox, "add", add);
      if (!r.ok()) return r;
    }
    return git(sandbox, "commit", o.copy().path(path).message(message));
  }

  public Results.GitResult gitCommit(String sandbox, String path, String message) { return gitCommit(sandbox, path, message, null); }

  private static Results.Session session(Map<String, Object> m) {
    return new Results.Session(str(m.get("name")), str(m.get("id")), str(m.get("image")), str(m.get("status")));
  }

  /** POST …/sessions: a shell that keeps cd and export between commands. name null: the daemon picks one. */
  public Results.Session createSession(String sandbox, String name, Options.Session o) {
    if (o == null) o = new Options.Session();
    Map<String, Object> body = new LinkedHashMap<>();
    if (name != null && !name.isEmpty()) body.put("name", name);
    if (o.image != null && !o.image.isEmpty()) body.put("image", o.image);
    if (o.env != null && !o.env.isEmpty()) body.put("env", o.env);
    return session(obj(t.json(new Request("POST", sb(sandbox, "sessions")).body(body).approval(o.approval))));
  }

  public Results.Session createSession(String sandbox, String name) { return createSession(sandbox, name, null); }

  /** GET …/sessions */
  public List<Results.Session> listSessions(String sandbox) {
    List<Results.Session> out = new ArrayList<>();
    for (Map<String, Object> m : objs(t.json(new Request("GET", sb(sandbox, "sessions"))))) out.add(session(m));
    return out;
  }

  /** POST …/sessions/{name}/exec: run one command line in the session. */
  public Results.ShellResult sessionExec(String sandbox, String session, String command, Options.Shell o) {
    if (o == null) o = new Options.Shell();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("command", command);
    if (o.timeout != null && !o.timeout.isZero()) body.put("timeout_s", seconds(o.timeout));
    Map<String, Object> m = obj(t.json(new Request("POST", sb(sandbox, "sessions", Transport.enc(session), "exec"))
        .body(body).approval(o.approval).timeout(slack(o.timeout))));
    return new Results.ShellResult((int) num(m.get("exit")), str(m.get("stdout")), str(m.get("stderr")), str(m.get("cwd")),
        num(m.get("duration_ms")), bool(m.get("timed_out")), bool(m.get("truncated")));
  }

  public Results.ShellResult sessionExec(String sandbox, String session, String command) {
    return sessionExec(sandbox, session, command, null);
  }

  /** Run a command in a session (default "main"), making the session on first use. */
  public Results.ShellResult shell(String sandbox, String command, Options.Shell o) {
    if (o == null) o = new Options.Shell();
    try {
      return sessionExec(sandbox, o.session, command, o);
    } catch (Kosa8Exception.NotFoundException e) {
      if (!String.valueOf(e.getMessage()).contains("no such session")) throw e;
    }
    createSession(sandbox, o.session, new Options.Session().approval(o.approval));
    return sessionExec(sandbox, o.session, command, o);
  }

  public Results.ShellResult shell(String sandbox, String command) { return shell(sandbox, command, null); }

  /** DELETE …/sessions/{name} */
  public Map<String, Object> deleteSession(String sandbox, String session, Options.Approval o) {
    return obj(t.json(new Request("DELETE", sb(sandbox, "sessions", Transport.enc(session))).approval(appr(o))));
  }

  public Map<String, Object> deleteSession(String sandbox, String session) { return deleteSession(sandbox, session, null); }

  /**
   * POST …/pty: a container with a terminal; returns its id. Connect to
   * {@code /kosa8/v1/sandboxes/{id}/containers/{cid}/tty} with any WebSocket client: binary
   * messages are the terminal, text messages JSON control.
   */
  public String startPty(String sandbox, Options.Pty o) {
    if (o == null) o = new Options.Pty();
    Map<String, Object> body = new LinkedHashMap<>();
    if (o.image != null && !o.image.isEmpty()) body.put("image", o.image);
    if (o.command != null && !o.command.isEmpty()) body.put("command", o.command);
    if (o.cols > 0 && o.rows > 0) {
      body.put("cols", o.cols);
      body.put("rows", o.rows);
    }
    if (o.env != null && !o.env.isEmpty()) body.put("env", o.env);
    return str(obj(t.json(new Request("POST", sb(sandbox, "pty")).body(body).approval(o.approval))).get("id"));
  }

  public String startPty(String sandbox) { return startPty(sandbox, null); }

  /** POST …/containers/{cid}/resize: a terminal's new size. */
  public Map<String, Object> resizePty(String sandbox, String container, int cols, int rows) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("cols", cols);
    body.put("rows", rows);
    return obj(t.json(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "resize")).body(body)));
  }

  /** POST …/previews: a signed link to a web server in the sandbox that expires. */
  public Results.Preview createPreview(String sandbox, int port, Options.Preview o) {
    if (o == null) o = new Options.Preview();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("port", port);
    if (o.session != null && !o.session.isEmpty()) body.put("session", o.session);
    if (o.container != null && !o.container.isEmpty()) body.put("container", o.container);
    if (o.expiresIn != null && !o.expiresIn.isZero()) body.put("expires_in_s", seconds(o.expiresIn));
    Map<String, Object> m = obj(t.json(new Request("POST", sb(sandbox, "previews")).body(body).approval(o.approval)));
    return new Results.Preview(str(m.get("url")), str(m.get("token")), str(m.get("expires_at")), str(m.get("container")),
        (int) num(m.get("port")), str(m.get("listen")));
  }

  public Results.Preview createPreview(String sandbox, int port) { return createPreview(sandbox, port, null); }

  /** POST /kosa8/v1/previews/rotate: end every preview link at once. */
  public Map<String, Object> revokePreviews(Options.Approval o) {
    return obj(t.json(new Request("POST", "/kosa8/v1/previews/rotate").approval(appr(o))));
  }

  public Map<String, Object> revokePreviews() { return revokePreviews(null); }

  /** POST …/lifecycle: change auto-stop, auto-delete, TTL or ephemeral; returns the sandbox. */
  public Map<String, Object> setLifecycle(String sandbox, Options.Lifecycle o) {
    if (o == null) o = new Options.Lifecycle();
    return obj(t.json(new Request("POST", sb(sandbox, "lifecycle")).body(o.body()).approval(o.approval)));
  }

  /** GET /kosa8/v1/templates */
  public List<Results.Template> listTemplates() {
    List<Results.Template> out = new ArrayList<>();
    for (Map<String, Object> m : objs(t.json(new Request("GET", "/kosa8/v1/templates"))))
      out.add(new Results.Template(str(m.get("name")), str(m.get("image")), num(m.get("size_bytes")), str(m.get("created")),
          str(m.get("dockerfile"))));
    return out;
  }

  /** DELETE /kosa8/v1/templates/{name} */
  public Map<String, Object> deleteTemplate(String name, Options.Approval o) {
    return obj(t.json(new Request("DELETE", "/kosa8/v1/templates/" + Transport.enc(name)).approval(appr(o))));
  }

  public Map<String, Object> deleteTemplate(String name) { return deleteTemplate(name, null); }

  /**
   * POST /kosa8/v1/templates: build contextDir's Dockerfile in a throwaway sandbox and keep the
   * image as a template. The folder is sent as a tar, leaving out what .dockerignore lists.
   * Returns the final frame (its "image" is the template's image); a failed build throws
   * StreamException. The client's default timeout does not apply.
   */
  public Map<String, Object> buildTemplate(String name, java.nio.file.Path contextDir, Options.Build o) {
    if (o == null) o = new Options.Build();
    StringBuilder path = new StringBuilder("/kosa8/v1/templates?name=").append(Transport.enc(name))
        .append("&dockerfile=").append(Transport.enc(o.dockerfile));
    if (o.buildArgs != null)
      for (Map.Entry<String, String> e : o.buildArgs.entrySet())
        path.append("&buildarg=").append(Transport.enc(e.getKey() + "=" + e.getValue()));
    if (o.egress != null && !o.egress.isEmpty()) path.append("&egress=").append(Transport.enc(o.egress));
    if (o.replace) path.append("&replace=1");
    java.nio.file.Path tar = null;
    try {
      tar = java.nio.file.Files.createTempFile("kosa8-template-ctx-", ".tar");
      try (java.io.OutputStream out = new java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(tar))) {
        Tar.context(contextDir, o.dockerfile, out);
      }
      final Consumer<byte[]> onOutput = o.onOutput;
      java.util.concurrent.atomic.AtomicReference<Map<String, Object>> done = new java.util.concurrent.atomic.AtomicReference<>();
      try (InputStream body = java.nio.file.Files.newInputStream(tar)) {
        // A build takes minutes: no timeout, whatever the client's default.
        t.framesRaw(new Request("POST", path.toString()).body(body).approval(o.approval).timeout(Duration.ZERO), f -> {
          if (done.get() != null) return;
          Map<String, Object> m = obj(f);
          if (bool(m.get("done"))) {
            String err = str(m.get("error"));
            if (!err.isEmpty()) throw new Kosa8Exception.StreamException(err, "", "", err);
            done.set(m);
            return;
          }
          if (onOutput != null && m.get("data") instanceof String d && !d.isEmpty()) onOutput.accept(java.util.Base64.getDecoder().decode(d));
        });
      }
      Map<String, Object> last = done.get();
      if (last == null) throw new Kosa8Exception.StreamException("template build stream ended unexpectedly (no final frame)", "", "", null);
      return last;
    } catch (java.io.IOException e) {
      throw new Kosa8Exception("pack the build context " + contextDir + ": " + e.getMessage(), e);
    } finally {
      if (tar != null) {
        try {
          java.nio.file.Files.deleteIfExists(tar);
        } catch (java.io.IOException ignored) {
          // a temp file
        }
      }
    }
  }

  public Map<String, Object> buildTemplate(String name, java.nio.file.Path contextDir) { return buildTemplate(name, contextDir, null); }

  /** A handle bound to one sandbox. */
  public Sandbox sandbox(String id) { return new Sandbox(this, id); }
}
