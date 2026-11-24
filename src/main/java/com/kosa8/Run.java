package com.kosa8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One RL training run on kosa8 Cloud, shown on app.kosa8.com (RL runs).
 *
 * <pre>{@code
 * try (Run run = Run.start(cloud, "my-grpo-run", new Run.Options())) {
 *   CloudSandbox base = cloud.sandboxes().create(new CloudOptions.Create().template("python"));
 *   Rollouts rs = run.map(base, tasks, (box, task) -> { ... ; return null; },
 *       new Run.MapOptions().gradeCmd(List.of("python", "/grader/test.py")));
 * }
 * }</pre>
 *
 * <p>Starting it creates the run (or attaches to {@link Options#runId}) and starts a lease: a daemon thread sends
 * a heartbeat for the run's sandboxes (labelled {@code kosa8.run=<id>}, leased for leaseS) every interval and
 * records buffered rollouts. If the process dies the heartbeats stop and kosa8 Cloud deletes the sandboxes
 * leaseS later. {@link #finish} (and {@link #close}, as "finished") records what is left, drains the run's
 * sandboxes (drainOnExit) and marks the run. To mark it failed on an exception use {@link #with}, or call
 * {@code finish("failed")} yourself before close.
 */
public final class Run implements AutoCloseable {
  /** Rows per POST /v1/rl/runs/{id}/rollouts, at most. */
  public static final int MAX_ROWS_PER_CALL = 500;
  /** A sandbox of a run is deleted this long after the last heartbeat, by default. */
  public static final int DEFAULT_LEASE_S = 600;
  /** Masked by default: the sandbox or the platform failed, not the policy (error: your solve threw). */
  public static final Set<String> DEFAULT_MASK = Set.of("infra", "quota", "error");

  /** How a run starts. */
  public static final class Options {
    String project;
    Map<String, Object> config;
    String runId;
    int leaseS = DEFAULT_LEASE_S;
    boolean drainOnExit = true;
    int flushEvery = 100;
    Duration heartbeat;

    public Options project(String v) { project = v; return this; }
    /** The run's hyperparameters, shown with it (at most 16 KiB as JSON). */
    public Options config(Map<String, Object> v) { config = v; return this; }
    /** Attach to an existing run (to keep recording after a restart) instead of creating one. */
    public Options runId(String v) { runId = CloudOptions.checkRunId(v); return this; }
    /** How long its sandboxes outlive the last heartbeat (default 600 s). */
    public Options leaseS(int v) { leaseS = CloudOptions.checkLease(v, "leaseS"); return this; }
    /** Delete the run's sandboxes when it finishes (default true). */
    public Options drainOnExit(boolean v) { drainOnExit = v; return this; }
    /** Send rollouts once this many are buffered, 1 to 500 (default 100). */
    public Options flushEvery(int v) {
      if (v < 1 || v > MAX_ROWS_PER_CALL) throw new IllegalArgumentException("flushEvery must be from 1 to " + MAX_ROWS_PER_CALL);
      flushEvery = v;
      return this;
    }
    /** How often to heartbeat and flush (default a third of leaseS, at least 5 s). */
    public Options heartbeat(Duration v) { heartbeat = v; return this; }
  }

  /** What a step's map does with each rollout. See {@link Run#map}. */
  public static final class MapOptions {
    List<String> gradeCmd;
    java.util.function.Function<Object, Map<String, byte[]>> files;
    Integer gradeTimeoutS;
    Map<String, String> gradeEnv;
    Reward reward;
    int groupSize = 1;
    Integer step;
    int retries = 2;
    int concurrency = 16;
    Duration timeout;
    Set<String> mask = DEFAULT_MASK;
    boolean keep;

    /** The sealed grader's command, run in a network-less fork of the solved sandbox with the task's hidden files. */
    public MapOptions gradeCmd(List<String> v) { gradeCmd = List.copyOf(v); return this; }
    /** The hidden files of a task (default: a Map task's "files" or "hidden_files", values byte[] or String). */
    public MapOptions files(java.util.function.Function<Object, Map<String, byte[]>> v) { files = v; return this; }
    /** 1 to 90 seconds. */
    public MapOptions gradeTimeoutS(int v) { gradeTimeoutS = v; return this; }
    public MapOptions gradeEnv(Map<String, String> v) { gradeEnv = v; return this; }
    /** Without gradeCmd: the reward of a rollout (a Number, or a Map with "reward"). */
    public MapOptions reward(Reward v) { reward = v; return this; }
    /** Samples per task (GRPO's G; default 1). */
    public MapOptions groupSize(int v) {
      if (v < 1) throw new IllegalArgumentException("groupSize must be at least 1");
      groupSize = v;
      return this;
    }
    /** The training step (default: this run's count of maps). */
    public MapOptions step(int v) { step = v; return this; }
    /** Retries of a retryable failure on a fresh sandbox (a failed grade is retried alone first; default 2). */
    public MapOptions retries(int v) {
      if (v < 0) throw new IllegalArgumentException("retries must be 0 or more");
      retries = v;
      return this;
    }
    public MapOptions concurrency(int v) {
      if (v < 1) throw new IllegalArgumentException("concurrency must be at least 1");
      concurrency = v;
      return this;
    }
    /** Caps solve; past it the sandbox is deleted (failure class timeout). */
    public MapOptions timeout(Duration v) { timeout = v; return this; }
    /** The failure classes masked (reward null; default infra, quota, error). Add timeout for compact filtering. */
    public MapOptions mask(Set<String> v) {
      Set<String> known = Set.of("ok", "exit_nonzero", "timeout", "oom", "infra", "quota", "client", "error");
      if (!known.containsAll(v)) throw new IllegalArgumentException("unknown failure classes in mask: " + v);
      mask = Set.copyOf(v);
      return this;
    }
    /** Keep each solved sandbox (leased) instead of deleting it. */
    public MapOptions keep(boolean v) { keep = v; return this; }
  }

  /** A reward computed in the client: {@code (sandbox, task, output) -> reward}. */
  @FunctionalInterface
  public interface Reward {
    Object apply(CloudSandbox sandbox, Object task, Object output) throws Exception;
  }

  /** The body of {@link #with}. */
  @FunctionalInterface
  public interface Body<X> {
    X apply(Run run) throws Exception;
  }

  /** A page of {@link #rollouts}: filters may be null. */
  public static final class RolloutQuery {
    final Map<String, Object> q = new LinkedHashMap<>();

    public RolloutQuery step(int v) { q.put("step", v); return this; }
    public RolloutQuery failureClass(String v) { q.put("failure_class", v); return this; }
    public RolloutQuery minReward(double v) { q.put("min_reward", v); return this; }
    public RolloutQuery maxReward(double v) { q.put("max_reward", v); return this; }
    public RolloutQuery sandboxId(String v) { q.put("sandbox_id", v); return this; }
    public RolloutQuery limit(int v) { q.put("limit", v); return this; }
    public RolloutQuery cursor(String v) { q.put("cursor", v); return this; }

    String query() {
      StringBuilder s = new StringBuilder();
      q.forEach((k, v) -> s.append('&').append(k).append('=').append(Transport.enc(String.valueOf(v))));
      return s.length() == 0 ? "" : "?" + s.substring(1);
    }
  }

  private final Kosa8Cloud cloud;
  private final Options o;
  private volatile String id;
  private volatile Map<String, Object> info = new LinkedHashMap<>();
  private volatile String status;
  private final List<Map<String, Object>> buf = new ArrayList<>();
  // The chunk being sent and its Idempotency-Key, kept together (guarded by buf) until kosa8 Cloud answers: a later
  // flush resends the same rows with the same key, so a send whose answer was lost is stored once.
  private List<Map<String, Object>> inflight;
  private String inflightKey;
  private final Object flushLock = new Object();
  private ScheduledExecutorService ticker;
  private volatile boolean open;
  private volatile int recorded;
  private volatile Throwable lastError;
  private int maps;

  private Run(Kosa8Cloud cloud, Options o) {
    if (cloud == null) throw new IllegalArgumentException("a run needs a Kosa8Cloud");
    this.cloud = cloud;
    this.o = o == null ? new Options() : o;
  }

  /** Create a run (or attach to options' runId) and start its lease. */
  public static Run start(Kosa8Cloud cloud, String name, Options options) {
    Run r = new Run(cloud, options);
    r.open(name);
    return r;
  }

  /** Attach to an existing run, to keep recording after a restart. */
  public static Run attach(Kosa8Cloud cloud, String runId, Options options) {
    Options o = options == null ? new Options() : options;
    o.runId(runId);
    return start(cloud, null, o);
  }

  /** Run body in a run that is marked finished when it returns and failed when it throws. */
  public static <X> X with(Kosa8Cloud cloud, String name, Options options, Body<X> body) throws Exception {
    Run run = start(cloud, name, options);
    boolean ok = false;
    try {
      X x = body.apply(run);
      ok = true;
      return x;
    } finally {
      run.finish(ok ? "finished" : "failed");
    }
  }

  /** The org's runs, newest first; filters may be null. */
  public static List<Map<String, Object>> list(Kosa8Cloud cloud, String project, String status, String name, Integer limit) {
    StringBuilder q = new StringBuilder();
    if (project != null) q.append("&project=").append(Transport.enc(project));
    if (status != null) q.append("&status=").append(Transport.enc(status));
    if (name != null) q.append("&name=").append(Transport.enc(name));
    if (limit != null) q.append("&limit=").append(limit);
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/rl/runs" + (q.length() == 0 ? "" : "?" + q.substring(1)), null)).get("runs"));
  }

  private void open(String name) {
    if (o.runId == null) {
      if (name == null || name.isEmpty()) throw new IllegalArgumentException("give the run a name, or Options.runId to attach to one");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("name", name);
      if (o.project != null) b.put("project", o.project);
      if (o.config != null) b.put("config", o.config);
      info = runOf(cloud.request("POST", "/v1/rl/runs", b));
      if (!(info.get("id") instanceof String s) || s.isEmpty()) throw new Kosa8Exception("kosa8 Cloud answered no run id");
      id = (String) info.get("id");
    } else {
      id = o.runId;
    }
    status = "running";
    open = true;
    long every = o.heartbeat != null ? Math.max(1, o.heartbeat.toMillis()) : Math.max(5000, o.leaseS * 1000L / 3);
    ticker = Executors.newSingleThreadScheduledExecutor(ForkMap.daemons("kosa8-lease"));
    ticker.scheduleWithFixedDelay(this::tick, every, every, TimeUnit.MILLISECONDS);
  }

  private static Map<String, Object> runOf(Object answer) {
    Map<String, Object> m = Kosa8Cloud.obj(answer);
    return m.get("run") instanceof Map<?, ?> ? Kosa8Cloud.obj(m.get("run")) : m;
  }

  /** One heartbeat and flush (what the lease thread does every interval). */
  void tick() {
    try {
      cloud.sandboxes().heartbeat(id);
    } catch (RuntimeException e) {
      lastError = e;
    }
    try {
      flush();
    } catch (RuntimeException e) {
      lastError = e; // kept buffered for the next tick
    }
  }

  public String id() { return id; }
  /** running, finished or failed. */
  public String status() { return status; }
  /** The run as the API last answered it. */
  public Map<String, Object> info() { return info; }
  /** Rollouts sent so far by this object. */
  public int recorded() { return recorded; }
  /** The last error a heartbeat or a background flush met, or null. */
  public Throwable lastError() { return lastError; }
  public int leaseS() { return o.leaseS; }
  /** The label on the run's sandboxes: {kosa8.run: id}. */
  public Map<String, String> label() { return Map.of(CloudOptions.RUN_LABEL, id); }

  private String p(String suffix) { return "/v1/rl/runs/" + Transport.enc(id) + suffix; }

  /** Buffer one rollout row ({step, group, index, reward, failure_class, exit, duration_ms, tokens, cost_usd, meta, stdout_tail}). */
  public void record(Map<String, ?> row) { recordAll(List.of(new LinkedHashMap<>(row))); }

  public void record(RolloutResult r) { recordAll(List.of(r.toRow())); }

  /** Buffer many (Maps or RolloutResults); sent in batches of flushEvery (at most 500 a call), every heartbeat, and at finish. */
  public void record(List<?> rows) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Object x : rows) {
      if (x instanceof RolloutResult r) out.add(r.toRow());
      else if (x instanceof Map<?, ?> m) out.add(new LinkedHashMap<>(Kosa8Cloud.obj(m)));
      else throw new IllegalArgumentException("a rollout is a Map or a RolloutResult, not " + x);
    }
    recordAll(out);
  }

  private void recordAll(List<Map<String, Object>> rows) {
    boolean full;
    synchronized (buf) {
      buf.addAll(rows);
      full = buf.size() >= o.flushEvery;
    }
    if (full) flush();
  }

  /** Rollouts buffered, not yet sent. */
  public int pending() { synchronized (buf) { return buf.size() + (inflight == null ? 0 : inflight.size()); } }

  /** Send every buffered rollout now, 500 a call; returns how many were recorded. On an error the unsent stay buffered. */
  public int flush() {
    int sent = 0;
    synchronized (flushLock) {
      while (true) {
        List<Map<String, Object>> chunk;
        String key;
        synchronized (buf) {
          if (inflight == null) {
            int n = Math.min(buf.size(), MAX_ROWS_PER_CALL);
            if (n == 0) return sent;
            inflight = new ArrayList<>(buf.subList(0, n));
            inflightKey = java.util.UUID.randomUUID().toString();
            buf.subList(0, n).clear();
          }
          chunk = inflight;
          key = inflightKey;
        }
        // On an error the chunk stays in flight with its key, for the next flush.
        Map<String, Object> r = Kosa8Cloud.obj(cloud.request("POST", p("/rollouts"), Map.of("rollouts", chunk), key));
        synchronized (buf) {
          inflight = null;
          inflightKey = null;
        }
        int n = r.get("recorded") instanceof Number x ? x.intValue() : chunk.size();
        sent += n;
        recorded += n;
      }
    }
  }

  /** The run with its summary (rewards, failure classes, live sandboxes, cost) and recommendations. */
  public Map<String, Object> summary() {
    Map<String, Object> r = Kosa8Cloud.obj(cloud.request("GET", p(""), null));
    if (r.get("run") instanceof Map<?, ?>) info = runOf(r);
    return r;
  }

  /** Rename it or set its status (running, finished, failed); null leaves a field as it is. */
  public Map<String, Object> update(String status, String name) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (status != null) b.put("status", status);
    if (name != null) b.put("name", name);
    if (b.isEmpty()) throw new IllegalArgumentException("give status or name");
    info = runOf(cloud.request("PATCH", p(""), b));
    return info;
  }

  /** One page of recorded rollouts, oldest first: {rollouts, next_cursor}. */
  public Map<String, Object> rollouts(RolloutQuery query) {
    return Kosa8Cloud.obj(cloud.request("GET", p("/rollouts") + (query == null ? "" : query.query()), null));
  }

  /** Every rollout as JSON lines. */
  public byte[] export() { return cloud.requestBytes("GET", p("/export?format=jsonl")); }

  /** Every rollout as JSON lines, written to path. */
  public byte[] export(Path path) throws IOException {
    byte[] b = export();
    Files.write(path, b);
    return b;
  }

  /** Delete the run's sandboxes now (the run stays open). */
  public DrainResult drain() { return cloud.sandboxes().drain(id); }

  /**
   * Record what is buffered, stop the lease, drain the run's sandboxes (drainOnExit) and mark the run finished
   * or failed. Safe to call twice; errors are kept in {@link #lastError()}, not thrown.
   */
  public void finish(String status) {
    if (!"finished".equals(status) && !"failed".equals(status)) throw new IllegalArgumentException("status is finished or failed");
    if (!open) return;
    open = false;
    if (ticker != null) ticker.shutdownNow();
    try {
      flush();
    } catch (RuntimeException e) {
      lastError = e;
    }
    if (o.drainOnExit) {
      try {
        drain();
      } catch (RuntimeException e) {
        lastError = e;
      }
    }
    try {
      update(status, null);
    } catch (RuntimeException e) {
      lastError = e;
    }
    this.status = status;
  }

  /** {@code finish("finished")}. */
  @Override
  public void close() { finish("finished"); }

  // --- rollouts --------------------------------------------------------------------------

  /**
   * One training step: solve(sandbox, task) for every task (groupSize samples each), concurrency at a time.
   *
   * <p>For each rollout: fork base (labelled with this run, leased), run solve in it, grade it in a sealed fork
   * with gradeCmd and the task's hidden files (recorded on the run by kosa8 Cloud), record it if the grader did
   * not, delete the sandbox. Without gradeCmd the reward is {@link MapOptions#reward}'s or solve's own return (a
   * Number, or a Map with "reward"). Retryable failures are retried on a fresh sandbox with backoff and jitter (a
   * failed grade alone first). What still fails in the mask comes back masked with reward null, never 0.
   * Results are in task order.
   */
  public <T> Rollouts map(CloudSandbox base, List<T> tasks, ForkMap.Fn<T, Object> solve, MapOptions options) {
    if (!open) throw new IllegalStateException("the run is not open");
    if (solve == null) throw new IllegalArgumentException("map needs a solve function");
    MapOptions m = options == null ? new MapOptions() : options;
    int step;
    synchronized (this) { step = m.step != null ? m.step : maps; maps++; }
    List<T> ts = new ArrayList<>(tasks == null ? List.of() : tasks);
    int n = ts.size() * m.groupSize;
    RetryPolicy policy = RetryPolicy.attempts(m.retries + 1);
    ForkMap.ChildPool pool = new ForkMap.ChildPool(cloud, base, null, id, o.leaseS);
    AtomicBoolean stop = new AtomicBoolean();
    if (n > 0) {
      try {
        pool.prefork(Math.min(n, m.concurrency));
      } catch (RuntimeException | Error e) {
        pool.close();
        throw e;
      }
    }
    List<RolloutResult> out = ForkMap.runPool(n, i -> rollout(ts, i, step, solve, m, policy, pool, stop), m.concurrency, stop, pool::close);
    return new Rollouts(out);
  }

  private <T> RolloutResult rollout(List<T> ts, int i, int step, ForkMap.Fn<T, Object> solve, MapOptions m, RetryPolicy policy,
      ForkMap.ChildPool pool, AtomicBoolean stop) {
    int ti = i / m.groupSize;
    T task = ts.get(ti);
    RolloutResult res = new RolloutResult(task, ti, i % m.groupSize, step);
    if (task instanceof Map<?, ?> tm) {
      for (String k : List.of("id", "task_id", "instance_id")) if (tm.get(k) != null) { res.meta.put("task", tm.get(k)); break; }
    }
    long t0 = System.nanoTime();
    Object out = null;
    for (int attempt = 1; attempt <= m.retries + 1; attempt++) {
      res.attempts = attempt;
      if (stop.get()) {
        res.failureClass = "error";
        res.error = "cancelled before it ran";
        break;
      }
      CloudSandbox child = null;
      try {
        child = pool.take();
        res.sandboxId = child.id();
        out = ForkMap.call(solve, child, task, m.timeout, pool);
        res.output = out;
        res.failureClass = "ok";
        res.error = null;
        boolean gave = fromOutput(res, out);
        if (m.gradeCmd != null) {
          applyGrade(res, gradeSettled(child, task, res, m, policy));
        } else if (m.reward != null) {
          if (!fromOutput(res, m.reward.apply(child, task, out))) throw new IllegalArgumentException("reward must return a number or {reward: x}");
        } else if (!gave) {
          throw new IllegalArgumentException("no reward: set gradeCmd (a sealed grader) or reward, or return a number or {reward: x} from solve");
        }
        break;
      } catch (Exception e) {
        FailureClass c = Kosa8Exception.classifyFailure(e);
        res.failureClass = c.failureClass();
        res.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        res.reward = null;
        if (!c.retryable() || attempt > m.retries) break;
        pool.release(child);
        child = null;
        cloud.sleeper().accept(policy.delay(e, attempt));
      } finally {
        if (child != null) {
          if (m.keep && !"infra".equals(res.failureClass)) pool.forget(child);
          else pool.release(child);
        }
      }
    }
    res.durationMs = (System.nanoTime() - t0) / 1_000_000;
    res.masked = m.mask.contains(res.failureClass);
    if (res.masked) res.reward = null;
    if (!res.recorded) {
      try {
        record(res);
      } catch (RuntimeException e) {
        lastError = e; // recording never fails a rollout
      }
    }
    return res;
  }

  /** Grade, retrying the grader alone on a retryable failure, each time with a new Idempotency-Key. */
  private GradeResult gradeSettled(CloudSandbox child, Object task, RolloutResult res, MapOptions m, RetryPolicy policy) {
    for (int a = 1; ; a++) {
      CloudOptions.Grade g = new CloudOptions.Grade().runId(id).rollout(res.step, String.valueOf(res.group), res.index, res.meta.isEmpty() ? null : new LinkedHashMap<>(res.meta));
      Map<String, byte[]> files = filesFor(task, m);
      if (files != null) g.files(files);
      if (m.gradeTimeoutS != null) g.timeoutS(m.gradeTimeoutS);
      if (m.gradeEnv != null) g.env(m.gradeEnv);
      try {
        GradeResult r = child.grade(m.gradeCmd, g);
        if (r.reward() == null && r.retryable() && a <= m.retries) {
          cloud.sleeper().accept(policy.delay(null, a));
          continue;
        }
        return r;
      } catch (RuntimeException e) {
        if (!Kosa8Exception.classifyFailure(e).retryable() || a > m.retries) throw e;
        cloud.sleeper().accept(policy.delay(e, a));
      }
    }
  }

  private static Map<String, byte[]> filesFor(Object task, MapOptions m) {
    if (m.files != null) return m.files.apply(task);
    if (task instanceof Map<?, ?> tm) {
      for (String k : List.of("files", "hidden_files")) {
        if (tm.get(k) instanceof Map<?, ?> f) {
          Map<String, byte[]> out = new LinkedHashMap<>();
          f.forEach((p, v) -> out.put(String.valueOf(p), v instanceof byte[] b ? b
              : String.valueOf(v).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          return out;
        }
      }
    }
    return null;
  }

  private static void applyGrade(RolloutResult res, GradeResult g) {
    res.reward = g.reward();
    res.metrics = new LinkedHashMap<>(g.metrics());
    res.exitCode = g.exitCode();
    res.stdout = g.stdout();
    res.stderr = g.stderr();
    res.failureClass = g.failureClass();
    res.graderId = g.graderId();
    if (g.reward() == null && g.error() != null) res.error = "grader: " + g.error() + (g.message() != null ? ": " + g.message() : "");
    res.recorded = Boolean.TRUE.equals(g.recorded());
  }

  /** Reward, metrics, tokens and meta from what solve (or reward) returned; true if it gave a reward. */
  static boolean fromOutput(RolloutResult res, Object out) {
    if (out instanceof Boolean) return false;
    if (out instanceof Number n) {
      res.reward = n.doubleValue();
      return true;
    }
    if (out instanceof Map<?, ?> m) {
      if (m.get("tokens") instanceof Number t) res.tokens = t.longValue();
      if (m.get("cost_usd") instanceof Number c) res.costUsd = c.doubleValue();
      if (m.get("meta") instanceof Map<?, ?> mm) mm.forEach((k, v) -> res.meta.put(String.valueOf(k), v));
      if (m.get("reward") instanceof Number r && !(m.get("reward") instanceof Boolean)) {
        res.reward = r.doubleValue();
        if (m.get("metrics") instanceof Map<?, ?> mt) res.metrics = new LinkedHashMap<>(Kosa8Cloud.obj(mt));
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() { return "Run(id=" + id + ", status=" + status + ")"; }
}
