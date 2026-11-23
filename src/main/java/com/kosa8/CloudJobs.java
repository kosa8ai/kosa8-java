package com.kosa8;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code cloud.jobs()}: hosted jobs. kosa8 runs every task's rollout itself: each task gets its own sandbox
 * (from template, image or snapshot), the solver runs there with the task's input in $KOSA8_TASK_INPUT, then the
 * sealed grader scores a copy with no network. Infra failures are retried retries_on_infra times, then recorded
 * masked (reward null). Results land in an RL run ({@code job.run_id}).
 *
 * <p>A job spec is the API's JSON, snake_case: name, exactly one of template / image / snapshot (or a benchmark
 * {name, instances}), tasks [{id, input?, files?}], solver {cmd, timeout_s, env?}, grader {cmd, timeout_s?, env?,
 * files? (path: base64)}, and optionally project, size, concurrency, retries_on_infra, lease_s, budget_usd.
 */
public final class CloudJobs {
  /** The statuses a job ends in. */
  public static final Set<String> DONE = Set.of("done", "failed", "cancelled", "budget_reached");

  private final Kosa8Cloud cloud;

  CloudJobs(Kosa8Cloud cloud) { this.cloud = cloud; }

  static Map<String, Object> check(Map<String, ?> spec) {
    if (spec == null || !(spec.get("name") instanceof String n) || n.isEmpty()) throw new IllegalArgumentException("a job needs a name");
    int sources = 0;
    for (String k : List.of("template", "image", "snapshot", "benchmark")) if (spec.get(k) != null) sources++;
    if (sources != 1) throw new IllegalArgumentException("give exactly one of template, image, snapshot or benchmark");
    if (!(spec.get("solver") instanceof Map<?, ?>)) throw new IllegalArgumentException("a job needs a solver {cmd, timeout_s}");
    if (!(spec.get("grader") instanceof Map<?, ?>)) throw new IllegalArgumentException("a job needs a grader {cmd}");
    return new LinkedHashMap<>(spec);
  }

  /** The job of an answer ({job}, with job_id / run_id on a submit), always with id and run_id when sent. */
  static Map<String, Object> jobOf(Object answer) {
    Map<String, Object> a = Kosa8Cloud.obj(answer);
    Map<String, Object> job = new LinkedHashMap<>(Kosa8Cloud.obj(a.get("job")));
    if (a.get("job_id") != null && job.get("id") == null) job.put("id", a.get("job_id"));
    if (a.get("run_id") != null && job.get("run_id") == null) job.put("run_id", a.get("run_id"));
    return job;
  }

  /** Start a job; answers it (id, run_id, status, ...). See {@link #submit(Map, String)}. */
  public Map<String, Object> submit(Map<String, ?> spec) { return submit(spec, null); }

  /**
   * Start a job. The call carries an Idempotency-Key (idempotencyKey, or a new UUID renewed after a 5xx),
   * so a retry after a dropped connection starts the job once.
   */
  public Map<String, Object> submit(Map<String, ?> spec, String idempotencyKey) {
    return jobOf(cloud.request("POST", "/v1/jobs", check(spec), idempotencyKey));
  }

  /** The job now: status, counts, by_failure_class, progress, cost_usd, eta_s. */
  public Map<String, Object> get(String id) { return jobOf(cloud.request("GET", "/v1/jobs/" + Transport.enc(id), null)); }

  /** The org's jobs, newest first; each argument may be null. */
  public List<Map<String, Object>> list(String status, String project, Integer limit, String cursor) {
    StringBuilder q = new StringBuilder();
    if (status != null) q.append("&status=").append(Transport.enc(status));
    if (project != null) q.append("&project=").append(Transport.enc(project));
    if (limit != null) q.append("&limit=").append(limit);
    if (cursor != null) q.append("&cursor=").append(Transport.enc(cursor));
    return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/jobs" + (q.length() == 0 ? "" : "?" + q.substring(1)), null)).get("jobs"));
  }

  public List<Map<String, Object>> list() { return list(null, null, null, null); }

  /** Stop starting rollouts and drain the job's sandboxes; answers the job. */
  public Map<String, Object> cancel(String id) {
    return jobOf(cloud.request("POST", "/v1/jobs/" + Transport.enc(id) + "/cancel", new LinkedHashMap<>()));
  }

  /**
   * What a job would use: sandbox_hours and usd_list as {low, high} and included_hours_left on the plan. Takes
   * submit's spec; sample n (1 to 50) starts a pilot job of the first n tasks (its id in sample.job_id), and
   * sampleJobId estimates from that finished pilot's measured durations. Both may be null, not both set.
   */
  public Map<String, Object> estimate(Map<String, ?> spec, Integer sample, String sampleJobId) {
    if (sample != null && sampleJobId != null) throw new IllegalArgumentException("give sample or sampleJobId, not both");
    Map<String, Object> b = check(spec);
    if (sample != null) b.put("sample", sample);
    if (sampleJobId != null) b.put("sample_job_id", sampleJobId);
    return Kosa8Cloud.obj(cloud.request("POST", "/v1/jobs/estimate", b));
  }

  /** Poll every interval until the job finishes ({@link #DONE}); TimeoutException after timeout (the job keeps running). */
  public Map<String, Object> waitFor(String id, Duration timeout, Duration interval) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (true) {
      Map<String, Object> j = get(id);
      if (DONE.contains(String.valueOf(j.get("status")))) return j;
      if (System.nanoTime() > deadline) {
        throw new Kosa8Exception.TimeoutException("job " + id + " was still " + j.get("status") + " after " + timeout.toSeconds() + " s",
            "GET", "/v1/jobs/" + Transport.enc(id));
      }
      cloud.sleeper().accept(interval);
    }
  }
}
