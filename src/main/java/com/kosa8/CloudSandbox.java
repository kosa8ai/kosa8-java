package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One kosa8 Cloud sandbox. {@link #info()} is its state as of the last call; the typed getters read it.
 *
 * <p>{@link #files()}, {@link #checkpoint()}, {@link #checkpoints()}, {@link #rewind(String)},
 * {@link #fork(int)} and {@link #ports()} need the host's data plane. On hosts without it the API
 * answers 501 ({@code not_available} / {@code not_supported}), thrown as
 * {@link Kosa8Exception.UnsupportedException}. Not every kosa8 Cloud host has it yet.
 */
public final class CloudSandbox {
  private final Kosa8Cloud cloud;
  private volatile Map<String, Object> info;

  CloudSandbox(Kosa8Cloud cloud, Map<String, Object> info) {
    this.cloud = cloud;
    this.info = info;
  }

  public String id() { return String.valueOf(info.get("id")); }

  /** creating, running, paused, sleeping, stopped, error, deleted, host_removed or host_lost. */
  public String state() { return info.get("state") instanceof String s ? s : ""; }

  /** The sandbox as the cloud last described it (template, cpus, mem_mb, region, cost_usd, ...). */
  public Map<String, Object> info() { return info; }

  /** The template it was made from, e.g. base or python. */
  public String template() { return s(info.get("template")); }

  /** Its region, or null. */
  public String region() { return s(info.get("region")); }

  /** The sandbox it was forked from, or null. */
  public String parentId() { return s(info.get("parent_id")); }

  /** How it started: pool, restore, cold or fork; null when not known. */
  public String source() { return s(info.get("source")); }

  /** The kosa8 Store version it was started (or forked) from, or null. */
  public String originItemVersion() { return s(info.get("origin_item_version")); }

  /** When it is deleted automatically (Unix ms), or null for never. */
  public Long deleteAfter() { return l(info.get("delete_after")); }

  /** Idle seconds before it sleeps (0 never), or null for the org's default. */
  public Integer sleepAfterS() {
    Long v = l(info.get("sleep_after_s"));
    return v == null ? null : v.intValue();
  }

  /** When it was made (Unix ms), or null. */
  public Long createdAt() { return l(info.get("created_at")); }

  /** Whether it runs on the org's own (bring-your-own-cloud) host. */
  public boolean ownHost() { return Boolean.TRUE.equals(info.get("own_host")); }

  /** Whether its host is not answering right now. */
  public boolean hostUnreachable() { return Boolean.TRUE.equals(info.get("host_unreachable")); }

  /** The cost estimate so far in USD, or null. */
  public Double costUsd() { return info.get("cost_usd") instanceof Number n ? n.doubleValue() : null; }

  /** Its last error, or null. */
  public String lastError() { return s(info.get("last_error")); }

  /** Whether every exec runs in one long-lived container. */
  public boolean persistent() { return Boolean.TRUE.equals(info.get("persistent")); }

  /** Its name, or null. */
  public String name() { return s(info.get("name")); }

  /** The OCI image of a sized create, or null. */
  public String image() { return s(info.get("image")); }

  /** The durable snapshot it was made from, or null. */
  public String snapshotId() { return s(info.get("snapshot_id")); }

  /** shared or dedicated. */
  public String cpu() { return s(info.get("cpu")); }

  /** Its labels ({} when none). */
  public Map<String, String> labels() {
    Map<String, String> out = new LinkedHashMap<>();
    if (info.get("labels") instanceof Map<?, ?> m) m.forEach((k, v) -> out.put(String.valueOf(k), String.valueOf(v)));
    return out;
  }

  /** Poll every second, for at most 10 minutes, until it is no longer creating. See {@link #waitRunning(java.time.Duration, java.time.Duration)}. */
  public CloudSandbox waitRunning() { return waitRunning(java.time.Duration.ofMinutes(10), java.time.Duration.ofSeconds(1)); }

  /**
   * Poll until it is no longer creating (a sized create boots cold); throws Kosa8Exception if it ends in
   * any state but running, TimeoutException after timeout.
   */
  public CloudSandbox waitRunning(java.time.Duration timeout, java.time.Duration interval) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while ("creating".equals(state())) {
      if (System.nanoTime() > deadline) {
        throw new Kosa8Exception.TimeoutException("sandbox " + id() + " was still creating after " + timeout.toSeconds() + " s", "GET", p(""));
      }
      pause(interval);
      refresh();
    }
    if (!"running".equals(state())) {
      throw new Kosa8Exception("sandbox " + id() + " is " + state() + ", not running" + (lastError() != null ? ": " + lastError() : ""));
    }
    return this;
  }

  static void pause(java.time.Duration d) {
    try {
      Thread.sleep(Math.max(0, d.toMillis()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Kosa8Exception("interrupted while waiting");
    }
  }

  /** Start a shell command that keeps running after this returns. See {@link #execBackground(List, String, Map)}. */
  public CloudBackgroundExec execBackground(String cmd) { return execBackground(List.of("sh", "-c", cmd), null, null); }

  /**
   * Start a command that keeps running after this returns (no 30 s limit); poll it with
   * {@link #backgroundExec(String)} or {@link #waitBackground(String, java.time.Duration, java.time.Duration)}.
   */
  public CloudBackgroundExec execBackground(List<String> cmd, String image, Map<String, String> env) {
    if (cmd == null || cmd.isEmpty()) throw new IllegalArgumentException("execBackground needs a command");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("cmd", List.copyOf(cmd));
    b.put("background", true);
    if (image != null) b.put("image", image);
    if (env != null && !env.isEmpty()) b.put("env", CloudOptions.envList(env));
    return CloudBackgroundExec.of(Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/exec"), b)).get("exec")));
  }

  /** A background command: running or exited, its exit code and its output so far. */
  public CloudBackgroundExec backgroundExec(String execId) {
    if (execId == null || execId.isEmpty()) throw new IllegalArgumentException("backgroundExec needs an exec id (bg_...)");
    return CloudBackgroundExec.of(Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", p("/exec/" + Transport.enc(execId)), null)).get("exec")));
  }

  /** Poll a background command every interval until it exits; TimeoutException after timeout. */
  public CloudBackgroundExec waitBackground(String execId, java.time.Duration timeout, java.time.Duration interval) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      CloudBackgroundExec e = backgroundExec(execId);
      if (e.done()) return e;
      if (System.nanoTime() > deadline) {
        throw new Kosa8Exception.TimeoutException("background command " + execId + " was still running after " + timeout.toSeconds() + " s", "GET", p("/exec"));
      }
      pause(interval);
    }
  }

  /** Save it as a durable snapshot with no name. See {@link #snapshot(String)}. */
  public CloudSnapshot snapshot() { return snapshot(null); }

  /** Save it as a durable snapshot (memory, processes, disk), kept until deleted; name may be null. */
  public CloudSnapshot snapshot(String name) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (name != null) b.put("name", name);
    return CloudSnapshot.of(Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/snapshots"), b)).get("snapshot")));
  }

  /** Its ports at https URLs. */
  public CloudPreviews previews() { return new CloudPreviews(this); }

  /** Its cron schedules. */
  public CloudSchedules schedules() { return new CloudSchedules(this); }

  /** A terminal URL with the default size and a login shell. See {@link #terminal(Integer, Integer, List)}. */
  public Map<String, Object> terminal() { return terminal(null, null, null); }

  /**
   * A short-lived wss:// URL for an interactive terminal (url, expires_at, protocol); null arguments take the
   * defaults. The URL carries its own token: keep it secret.
   */
  public Map<String, Object> terminal(Integer cols, Integer rows, List<String> command) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (cols != null) b.put("cols", cols);
    if (rows != null) b.put("rows", rows);
    if (command != null) b.put("command", List.copyOf(command));
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/terminal"), b)).get("terminal"));
  }

  /** The egress policy it runs with: internet, allow, deny, presets, source. */
  public Map<String, Object> egress() { return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", p("/egress"), null)).get("egress")); }

  /** Change what it may reach, at once (a sleeping one when it wakes). Null lists are empty. */
  public Map<String, Object> setEgress(boolean internet, List<String> allow, List<String> deny, List<String> presets) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("PUT", p("/egress"), CloudOptions.egressBody(internet, allow, deny, presets))).get("egress"));
  }

  /** Back to the organization's default egress policy. */
  public Map<String, Object> resetEgress() { return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("DELETE", p("/egress"), null)).get("egress")); }

  /** The names of the secrets it holds. */
  public List<String> secrets() {
    List<String> out = new ArrayList<>();
    if (Kosa8Cloud.obj(cloud.request("GET", p("/secrets"), null)).get("secrets") instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
    return out;
  }

  /** Run a Python cell in the default context. See {@link #runCode(String, String, Double)}. */
  public Map<String, Object> runCode(String code) { return runCode(code, null, null); }

  /**
   * Run a Python cell in a stateful context (null: the default): globals persist, charts come back as images
   * in results. A cell that raised has error set; it is not an exception. Needs a persistent sandbox with python3.
   */
  public Map<String, Object> runCode(String code, String context, Double timeoutS) {
    if (code == null || code.isEmpty()) throw new IllegalArgumentException("runCode needs code");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("code", code);
    b.put("language", "python");
    if (context != null) b.put("context", context);
    if (timeoutS != null) b.put("timeout_s", timeoutS);
    return Kosa8Cloud.obj(cloud.request("POST", p("/code"), b));
  }

  /** Its code contexts: name, execution_count. */
  public List<Map<String, Object>> codeContexts() {
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", p("/code/contexts"), null)).get("contexts"));
  }

  /** A new code context (name null: the server picks one). */
  public Map<String, Object> createCodeContext(String name) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (name != null) b.put("name", name);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/code/contexts"), b)).get("context"));
  }

  public void resetCodeContext(String name) { cloud.request("POST", p("/code/contexts/" + Transport.enc(name) + "/reset"), new LinkedHashMap<>()); }

  public void deleteCodeContext(String name) { cloud.request("DELETE", p("/code/contexts/" + Transport.enc(name)), null); }

  /** CPU, memory, disk and network use, read on the host. */
  public Map<String, Object> metrics() { return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", p("/metrics"), null)).get("metrics")); }

  /** Its last commands (limit null: the server's default), with exit codes and output tails. */
  public List<Map<String, Object>> logs(Integer limit) {
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", p("/logs") + (limit == null ? "" : "?limit=" + limit), null)).get("entries"));
  }

  /** Replace its labels (null or empty clears them); updates {@link #info()}. */
  public Map<String, String> setLabels(Map<String, String> labels) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("labels", labels == null || labels.isEmpty() ? null : CloudOptions.checkLabels(labels));
    Map<String, Object> r = Kosa8Cloud.obj(cloud.request("PUT", p("/labels"), b));
    Map<String, Object> next = new LinkedHashMap<>(info);
    next.put("labels", r.get("labels") instanceof Map<?, ?> ? r.get("labels") : new LinkedHashMap<>());
    info = next;
    return labels();
  }

  @Override
  public String toString() { return "CloudSandbox(id=" + id() + ", state=" + state() + ")"; }

  String p(String suffix) { return "/v1/sandboxes/" + Transport.enc(id()) + suffix; }

  Kosa8Cloud cloud() { return cloud; }

  /** Run a command through {@code sh -c} and wait for it. See {@link #exec(List, String, Map)}. */
  public CloudExecResult exec(String cmd) { return exec(List.of("sh", "-c", cmd), null, null); }

  /** Run a command and wait for it. See {@link #exec(List, String, Map)}. */
  public CloudExecResult exec(List<String> cmd) { return exec(cmd, null, null); }

  public CloudExecResult exec(String cmd, String image, Map<String, String> env) {
    return exec(List.of("sh", "-c", cmd), image, env);
  }

  /**
   * Run a command in image (null: the template's own) with env added, and wait for it. A sleeping
   * sandbox wakes for it.
   *
   * <p>Each exec runs in a fresh container: only {@code /workspace} persists, so {@code cd},
   * {@code export} and packages installed elsewhere are gone by the next one. kosa8 Cloud cuts a
   * buffered exec off after about 30 s, thrown as {@link Kosa8Exception.ExecTimeoutException}; run
   * long work in the background ({@code nohup sh -c '...' > /workspace/job.log 2>&1 &}) and poll it.
   * The request (command and env) is at most 64 KiB (413 {@code too_large}).
   */
  public CloudExecResult exec(List<String> cmd, String image, Map<String, String> env) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("cmd", List.copyOf(cmd));
    if (image != null) b.put("image", image);
    if (env != null && !env.isEmpty()) {
      List<String> kv = new ArrayList<>();
      env.forEach((k, v) -> kv.add(k + "=" + v));
      b.put("env", kv);
    }
    Map<String, Object> r = Kosa8Cloud.obj(cloud.request("POST", p("/exec"), b));
    Integer exit = r.get("exit") instanceof Number n ? n.intValue() : null;
    Long woke = r.get("woke_ms") instanceof Number n ? n.longValue() : null;
    return new CloudExecResult(exit, str(r.get("stdout")), str(r.get("stderr")), woke);
  }

  private static String str(Object o) { return o instanceof String s ? s : ""; }

  static String s(Object o) { return o instanceof String s ? s : null; }

  static Long l(Object o) { return o instanceof Number n ? n.longValue() : null; }

  private CloudSandbox take(Object resp) {
    info = Kosa8Cloud.obj(Kosa8Cloud.obj(resp).get("sandbox"));
    return this;
  }

  /** Snapshot it and free its VM; it then costs only snapshot storage. */
  public CloudSandbox sleep() { return take(cloud.request("POST", p("/sleep"), null)); }

  /** Restore a sleeping sandbox, files and all. */
  public CloudSandbox wake() { return take(cloud.request("POST", p("/wake"), null)); }

  public CloudSandbox refresh() { return take(cloud.request("GET", p(""), null)); }

  public void delete() { cloud.request("DELETE", p(""), null); }

  /**
   * Change its lifetime or sleep timer:
   * {@code box.update(new CloudOptions.Update().ttlSeconds(7200))} (counted from now), or
   * {@code new CloudOptions.Update().clearLifetime()} to stop it being deleted automatically.
   * Throws IllegalArgumentException when nothing is set or the values are out of range.
   */
  public CloudSandbox update(CloudOptions.Update options) {
    if (options == null) throw new IllegalArgumentException("give ttlSeconds, deleteAfter, sleepAfterS or clearLifetime");
    return take(cloud.request("PATCH", p(""), options.body()));
  }

  /**
   * Files in the sandbox, at most 8 MiB each.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public CloudSandboxFiles files() { return new CloudSandboxFiles(this); }

  /** A checkpoint with no label. See {@link #checkpoint(String)}. */
  public CloudCheckpoint checkpoint() { return checkpoint(null); }

  /**
   * Save memory, processes and disk as a checkpoint and return it; the newest 10 are kept. label
   * (null for none) is at most 200 characters.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public CloudCheckpoint checkpoint(String label) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (label != null) {
      if (label.length() > 200) throw new IllegalArgumentException("a checkpoint label is at most 200 characters");
      b.put("label", label);
    }
    return CloudCheckpoint.of(Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/checkpoints"), b)).get("checkpoint")));
  }

  /**
   * Its checkpoints, oldest first. Does not wake a sleeping sandbox.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public List<CloudCheckpoint> checkpoints() {
    List<CloudCheckpoint> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", p("/checkpoints"), null)).get("checkpoints"))) {
      out.add(CloudCheckpoint.of(m));
    }
    return out;
  }

  /**
   * Go back to a checkpoint. The sandbox as it was is first saved as an automatic checkpoint,
   * returned as {@link CloudRewindResult#backup()}; {@link #info()} is updated.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public CloudRewindResult rewind(String checkpointId) {
    if (checkpointId == null || checkpointId.isEmpty()) throw new IllegalArgumentException("rewind needs a checkpoint id");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("checkpoint_id", checkpointId);
    Map<String, Object> r = Kosa8Cloud.obj(cloud.request("POST", p("/rewind"), b));
    if (r.get("sandbox") instanceof Map<?, ?>) info = Kosa8Cloud.obj(r.get("sandbox"));
    CloudCheckpoint backup = r.get("backup") instanceof Map<?, ?> ? CloudCheckpoint.of(Kosa8Cloud.obj(r.get("backup"))) : null;
    return new CloudRewindResult(CloudCheckpoint.of(Kosa8Cloud.obj(r.get("checkpoint"))), backup, l(r.get("ms")));
  }

  /** One live copy of the sandbox as it is now. See {@link #fork(int)}. */
  public List<CloudSandbox> fork() { return fork(1); }

  /**
   * n (1 to 100) live copies of the sandbox as it is now, on its host. Each is held to the rules of
   * a create (payment, quotas); all or nothing.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public List<CloudSandbox> fork(int n) {
    if (n < 1 || n > CloudOptions.MAX_FORK) throw new IllegalArgumentException("n must be from 1 to " + CloudOptions.MAX_FORK + ", not " + n);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("n", n);
    List<CloudSandbox> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("POST", p("/fork"), b)).get("sandboxes"))) {
      out.add(new CloudSandbox(cloud, m));
    }
    return out;
  }

  /**
   * TCP ports listening in its running containers.
   *
   * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available}
   * / {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every
   * kosa8 Cloud host has it yet.
   */
  public List<CloudPort> ports() {
    List<CloudPort> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", p("/ports"), null)).get("ports"))) {
      out.add(CloudPort.of(m));
    }
    return out;
  }
}
