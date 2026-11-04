package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One kosa8 Cloud sandbox. {@link #info()} is its state as of the last call. */
public final class CloudSandbox {
  private final Kosa8Cloud cloud;
  private volatile Map<String, Object> info;

  CloudSandbox(Kosa8Cloud cloud, Map<String, Object> info) {
    this.cloud = cloud;
    this.info = info;
  }

  public String id() { return String.valueOf(info.get("id")); }

  /** creating, running, sleeping, stopped, deleted or error. */
  public String state() { return info.get("state") instanceof String s ? s : ""; }

  /** The sandbox as the cloud last described it (template, cpus, mem_mb, region, cost_usd, ...). */
  public Map<String, Object> info() { return info; }

  @Override
  public String toString() { return "CloudSandbox(id=" + id() + ", state=" + state() + ")"; }

  private String p(String suffix) { return "/v1/sandboxes/" + Transport.enc(id()) + suffix; }

  /** Run a command through {@code sh -c} and wait for it. A sleeping sandbox wakes for it. */
  public CloudExecResult exec(String cmd) { return exec(List.of("sh", "-c", cmd), null, null); }

  /** Run a command and wait for it. A sleeping sandbox wakes for it. */
  public CloudExecResult exec(List<String> cmd) { return exec(cmd, null, null); }

  public CloudExecResult exec(String cmd, String image, Map<String, String> env) {
    return exec(List.of("sh", "-c", cmd), image, env);
  }

  /** Run a command in image (null: the template's own) with env added. */
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

  private CloudSandbox update(Object resp) {
    info = Kosa8Cloud.obj(Kosa8Cloud.obj(resp).get("sandbox"));
    return this;
  }

  /** Snapshot it and free its VM; it then costs only snapshot storage. */
  public CloudSandbox sleep() { return update(cloud.request("POST", p("/sleep"), null)); }

  /** Restore a sleeping sandbox, files and all. */
  public CloudSandbox wake() { return update(cloud.request("POST", p("/wake"), null)); }

  public CloudSandbox refresh() { return update(cloud.request("GET", p(""), null)); }

  public void delete() { cloud.request("DELETE", p(""), null); }
}
