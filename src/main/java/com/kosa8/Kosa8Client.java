package com.kosa8;

import com.kosa8.Transport.Request;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
  public static final String VERSION = "0.1.0";
  private static final Pattern TRANSIENT = Pattern.compile(
      "connection refused|EOF|connection reset|broken pipe|no such file or directory|stream ended|ECONNREFUSED|ECONNRESET|unreachable",
      Pattern.CASE_INSENSITIVE);

  private final Transport t;
  private final CheckpointBook ckpts;

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
    /** For tests. */
    public Builder getenv(Function<String, String> v) { getenv = v; return this; }

    public Kosa8Client build() {
      Endpoint ep = Endpoint.resolve(host, socketPath, token, insecure, getenv);
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
    return obj(t.json(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "stdin")).body(data).approval(appr(o))));
  }

  /** POST …/containers/{cid}/stdin with a fixed body. */
  public Map<String, Object> writeStdin(String sandbox, String container, byte[] data, Options.Approval o) {
    return obj(t.json(new Request("POST", sb(sandbox, "containers", Transport.enc(container), "stdin")).body(data).approval(appr(o))));
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
    Map<String, Object> snap = snapshot(sandbox, new Options.Snapshot().id(next.id()).approval(appr(o)));
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
      throw new Kosa8Exception.CheckpointException("restore failed and " + sandbox + " is now stopped: " + e.getMessage()
          + " — recover with restoreSnapshot(\"" + target.id() + "\", new Options.Restore().name(\"" + sandbox + "\"))", e);
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

  /** A handle bound to one sandbox. */
  public Sandbox sandbox(String id) { return new Sandbox(this, id); }
}
