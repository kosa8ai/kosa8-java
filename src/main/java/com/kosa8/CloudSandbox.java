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
