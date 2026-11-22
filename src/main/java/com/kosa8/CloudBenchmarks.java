package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.benchmarks()}: ready-made benchmarks (such as swe-bench-verified) with their source and revision.
 * Each instance has its own environment; create a sandbox of one with
 * {@code new CloudOptions.Create().template("<benchmark>/<instance id>")}. An instance whose environment is still
 * being built answers 409 template_building with a Retry-After, which the SDK waits out (up to its retries);
 * {@link #prepare} builds environments ahead of use.
 */
public final class CloudBenchmarks {
  private final Kosa8Cloud cloud;

  CloudBenchmarks(Kosa8Cloud cloud) { this.cloud = cloud; }

  private static String p(String name) { return "/v1/benchmarks/" + Transport.enc(name); }

  /** The benchmarks, each with source, revision, instances and environment counts. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/benchmarks", null)).get("benchmarks")); }

  /** One benchmark. */
  public Map<String, Object> get(String name) { return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", p(name), null)).get("benchmark")); }

  /**
   * A page of its instances in the dataset's order, with their environment state: {instances, next_cursor}.
   * state (ready, building, queued, failed, not_built), limit and cursor may be null.
   */
  public Map<String, Object> instances(String name, String state, Integer limit, String cursor) {
    StringBuilder q = new StringBuilder();
    if (state != null) q.append("&state=").append(Transport.enc(state));
    if (limit != null) q.append("&limit=").append(limit);
    if (cursor != null) q.append("&cursor=").append(Transport.enc(cursor));
    return Kosa8Cloud.obj(cloud.request("GET", p(name) + "/instances" + (q.length() == 0 ? "" : "?" + q.substring(1)), null));
  }

  /** One instance: the solver's input and its environment's state and ETA. */
  public Map<String, Object> instance(String name, String id) {
    String path = String.format("/v1/benchmarks/%s/instances/%s", Transport.enc(name), Transport.enc(id));
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", path, null)).get("instance"));
  }

  /** Build these instances' environments ahead of use (at most 100 a call): {benchmark, instances, builds_at_once}. */
  public Map<String, Object> prepare(String name, List<String> instanceIds) { return prepareAny(name, List.copyOf(instanceIds), null); }

  /** Build the first n instances' environments (or, with seed, n chosen at random by that seed). */
  public Map<String, Object> prepare(String name, int n, Integer seed) { return prepareAny(name, n, seed); }

  private Map<String, Object> prepareAny(String name, Object instances, Integer seed) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("instances", instances);
    if (seed != null) b.put("seed", seed);
    return Kosa8Cloud.obj(cloud.request("POST", p(name) + "/prepare", b));
  }
}
