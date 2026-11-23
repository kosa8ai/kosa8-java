package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Option objects for {@link Kosa8Cloud}. Every setter returns {@code this}; only the fields set are sent. */
public final class CloudOptions {
  private CloudOptions() {}

  /** The shortest lifetime a sandbox may be given, in seconds (one minute). */
  public static final int MIN_TTL_S = 60;
  /** The longest lifetime a sandbox may be given, in seconds (90 days). */
  public static final int MAX_TTL_S = 7_776_000;
  /** The longest idle time before a sandbox sleeps, in seconds (7 days); 0 is never. */
  public static final int MAX_SLEEP_AFTER_S = 604_800;
  /** The most copies one fork makes. */
  public static final int MAX_FORK = 100;
  /** The sizes a sized create takes: vCPU x GiB. */
  public static final java.util.List<String> SIZES = java.util.List.of("2x4", "4x8", "8x16", "16x32");
  /**
   * The small sizes a sandbox create also takes (not a template build): a fraction of a shared vCPU reserved,
   * bursting above it while the host has idle CPU, for fleets of mostly idle sandboxes. 0.25x0.5 is cpus 0.25
   * and 512 MiB. Only hosts that run small sizes take them; elsewhere the create answers 501.
   */
  public static final java.util.List<String> SMALL_SIZES = java.util.List.of("0.25x0.5", "0.5x1", "1x2");
  /** Every size a sandbox create takes: {@link #SMALL_SIZES} then {@link #SIZES}. */
  public static final java.util.List<String> CREATE_SIZES = java.util.List.of("0.25x0.5", "0.5x1", "1x2", "2x4", "4x8", "8x16", "16x32");
  /** At most this many env variables on a create, each value at most 8 KiB. */
  public static final int MAX_ENV = 64;
  /** At most this many labels, each value at most 256 characters. */
  public static final int MAX_LABELS = 16;

  static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  static final Pattern SANDBOX_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
  static final Pattern SNAPSHOT_ID = Pattern.compile("snap_[A-Za-z0-9]{8,40}");
  static final Pattern SECRET_NAME = Pattern.compile("[a-z0-9-]{1,32}");
  static final Pattern LABEL_KEY = Pattern.compile("[A-Za-z0-9._/-]{1,63}");

  /** A new cloud sandbox: {@code cloud.sandboxes().create(new CloudOptions.Create().template("python").ttlSeconds(3600))}. */
  public static final class Create {
    String template;
    String region;
    Integer sleepAfterS;
    Integer ttlS;
    Long deleteAfter;
    Double acceptPrice;
    String size;
    Number cpus;
    Integer memMb;
    String image;
    Integer diskGb;
    Boolean record;
    Boolean aiGateway;
    Boolean persistent;
    Boolean docker;
    String cpu;
    String name;
    Map<String, String> labels;
    Map<String, String> env;
    java.util.List<String> secrets;
    Map<String, Object> egress;
    String snapshot;
    Integer leaseS;
    String idempotencyKey;
    boolean noWait;

    /**
     * vCPU x GiB: one of {@link CloudOptions#CREATE_SIZES}, e.g. "4x8", or a small size such as "0.25x0.5". A
     * size, cpus+memMb, image or diskGb boots the sandbox cold at that size; create waits for it unless
     * {@link #noWait()}.
     */
    public Create size(String v) { size = v; return this; }
    /** Or: a size by its parts (4 and 8192 for 4x8). */
    public Create cpus(int cpus, int memMb) { this.cpus = cpus; this.memMb = memMb; return this; }
    /** Or: a small size by its parts (0.25 and 512 for 0.25x0.5, 0.5 and 1024 for 0.5x1). */
    public Create cpus(double cpus, int memMb) {
      this.cpus = cpus == Math.rint(cpus) ? (Number) (int) cpus : (Number) cpus;
      this.memMb = memMb;
      return this;
    }
    /** Record its desktop session; the MP4 is saved when it is deleted ({@code cloud.recordings()}). */
    public Create record(boolean v) { record = v; return this; }
    /**
     * Route its model calls through the org's AI gateway ({@code cloud.ai()}): OPENAI_BASE_URL,
     * ANTHROPIC_BASE_URL, KOSA8_AI_GATEWAY_URL and KOSA8_AI_TOKEN are set in its env.
     */
    public Create aiGateway(boolean v) { aiGateway = v; return this; }
    /** An OCI image to run, e.g. kosa8-template/universal-24.04-20261115.2 (default size 2x4). */
    public Create image(String v) { image = v; return this; }
    /** The data disk holding / and /workspace, 8 to 200 GB (default 32). */
    public Create diskGb(int v) { diskGb = v; return this; }
    /** Run every exec in one long-lived container, so packages and background processes persist. */
    public Create persistent(boolean v) { persistent = v; return this; }
    /** Let it run its own Docker engine (start dockerd first); needs a size or image, makes it persistent. */
    public Create docker(boolean v) { docker = v; return this; }
    /** "shared" (default) or "dedicated": whole physical cores; never sleeps, no checkpoint or fork. */
    public Create cpu(String v) { cpu = v; return this; }
    /** Your label for it; {@code new CloudOptions.List().name(...)} finds it. */
    public Create name(String v) { name = v; return this; }
    /** Up to 16 key=value labels. */
    public Create labels(Map<String, String> v) { labels = v; return this; }
    /** Given to every command it runs (an exec's own env wins); values are never logged or returned. */
    public Create env(Map<String, String> v) { env = v; return this; }
    /** Secret names ({@code cloud.secrets()}) its egress broker adds; the sandbox holds placeholders. Needs a sized create. */
    public Create secrets(java.util.List<String> v) { secrets = v; return this; }
    /** Its own egress policy (see {@link CloudSandbox#setEgress}). */
    public Create egress(boolean internet, java.util.List<String> allow, java.util.List<String> deny, java.util.List<String> presets) {
      egress = egressBody(internet, allow, deny, presets);
      return this;
    }
    /** Start it from a durable snapshot (snap_...): then set only env, name, labels, a lifetime or sleepAfterS. */
    public Create snapshot(String v) { snapshot = v; return this; }
    /**
     * A lease: it is deleted leaseS seconds from now ({@value CloudOptions#MIN_LEASE_S} to
     * {@value CloudOptions#MAX_LEASE_S}) unless a heartbeat ({@code cloud.sandboxes().heartbeat(...)}) moves it
     * forward, so a crashed client leaks nothing. Not with ttlSeconds or deleteAfter.
     */
    public Create leaseS(int v) { leaseS = v; return this; }
    /**
     * The Idempotency-Key, sent unchanged on every retry. The default (a new UUID) is reused after no answer, a 429
     * or a 409, so a retried create makes one sandbox, and renewed after a 5xx, so that retry is a new attempt.
     */
    public Create idempotencyKey(String v) { idempotencyKey = Kosa8Cloud.checkIdempotencyKey(v); return this; }
    /** Return while a sized sandbox is still booting (state creating); call {@link CloudSandbox#waitRunning}. */
    public Create noWait() { noWait = true; return this; }

    /** A key from {@code cloud.templates()} (default the org's, base), or a Store item creator/name[@version]. */
    public Create template(String v) { template = v; return this; }
    /** e.g. eu-hel1; default the org's. */
    public Create region(String v) { region = v; return this; }
    /** Idle seconds before it sleeps, 0 to {@value CloudOptions#MAX_SLEEP_AFTER_S}; 0 never. */
    public Create sleepAfterS(int v) { sleepAfterS = v; return this; }
    /** Delete it this many seconds from now, {@value CloudOptions#MIN_TTL_S} to {@value CloudOptions#MAX_TTL_S}. */
    public Create ttlSeconds(int v) { ttlS = v; return this; }
    /** Or: delete it at this Unix time in milliseconds. Not with {@link #ttlSeconds}. */
    public Create deleteAfter(long unixMs) { deleteAfter = unixMs; return this; }
    /** A paid Store item only: its current price in USD, which the person agreed to. */
    public Create acceptPrice(double usd) { acceptPrice = usd; return this; }

    Map<String, Object> body() {
      checkLifetime(ttlS, deleteAfter);
      checkSleepAfter(sleepAfterS);
      if (acceptPrice != null && (acceptPrice.isNaN() || acceptPrice.isInfinite() || acceptPrice < 0)) {
        throw new IllegalArgumentException("acceptPrice must be a price in USD, 0 or more");
      }
      Map<String, Object> b = new LinkedHashMap<>();
      if (template != null) b.put("template", template);
      if (region != null) b.put("region", region);
      if (sleepAfterS != null) b.put("sleep_after_s", sleepAfterS);
      if (ttlS != null) b.put("ttl_s", ttlS);
      if (deleteAfter != null) b.put("delete_after", deleteAfter);
      if (leaseS != null) {
        if (ttlS != null || deleteAfter != null) throw new IllegalArgumentException("give leaseS or ttlSeconds/deleteAfter, not both: a lease is a TTL that heartbeats move forward");
        b.put("lease_s", checkLease(leaseS, "leaseS"));
      }
      if (acceptPrice != null) b.put("accept_price", acceptPrice);
      if (size != null) {
        if (!CREATE_SIZES.contains(size)) throw new IllegalArgumentException("size must be one of " + CREATE_SIZES + ", not " + size);
        b.put("size", size);
      }
      if (cpus != null) {
        double c = cpus.doubleValue();
        if (!(c > 0) || memMb == null || memMb <= 0) throw new IllegalArgumentException("cpus and memMb must be positive, e.g. 0.25 and 512");
        if (size != null) throw new IllegalArgumentException("give size or cpus and memMb, not both");
        b.put("cpus", cpus);
        b.put("mem_mb", memMb);
      }
      if (image != null) b.put("image", image);
      if (diskGb != null) {
        if (diskGb < 8 || diskGb > 200) throw new IllegalArgumentException("diskGb must be from 8 to 200, not " + diskGb);
        b.put("disk_gb", diskGb);
      }
      if (persistent != null) b.put("persistent", persistent);
      if (docker != null) b.put("docker", docker);
      if (record != null) b.put("record", record);
      if (aiGateway != null) b.put("ai_gateway", aiGateway);
      if (cpu != null) {
        if (!cpu.equals("shared") && !cpu.equals("dedicated")) throw new IllegalArgumentException("cpu must be shared or dedicated, not " + cpu);
        b.put("cpu", cpu);
      }
      if (name != null) {
        if (!SANDBOX_NAME.matcher(name).matches()) throw new IllegalArgumentException("name: 1 to 64 of letters, digits, '.', '_' or '-', not " + name);
        b.put("name", name);
      }
      if (labels != null) b.put("labels", checkLabels(labels));
      if (env != null) b.put("env", checkEnv(env));
      if (secrets != null) {
        for (String n : secrets) checkSecretName(n);
        b.put("secrets", java.util.List.copyOf(secrets));
      }
      if (egress != null) b.put("egress", egress);
      if (snapshot != null) {
        if (!SNAPSHOT_ID.matcher(snapshot).matches()) throw new IllegalArgumentException("snapshot is a snapshot id (snap_...), not " + snapshot);
        for (String k : java.util.List.of("template", "region", "size", "cpus", "image", "disk_gb", "persistent", "docker", "cpu")) {
          if (b.containsKey(k)) {
            throw new IllegalArgumentException("a create from a snapshot takes the snapshot's " + k + "; set only env, name, labels, a lifetime or sleepAfterS");
          }
        }
        b.put("snapshot", snapshot);
      }
      return b;
    }
  }

  static Map<String, Object> egressBody(boolean internet, java.util.List<String> allow, java.util.List<String> deny, java.util.List<String> presets) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("internet", internet);
    e.put("allow", allow == null ? java.util.List.of() : java.util.List.copyOf(allow));
    e.put("deny", deny == null ? java.util.List.of() : java.util.List.copyOf(deny));
    e.put("presets", presets == null ? java.util.List.of() : java.util.List.copyOf(presets));
    return e;
  }

  static Map<String, String> checkEnv(Map<String, String> env) {
    if (env.size() > MAX_ENV) throw new IllegalArgumentException("at most " + MAX_ENV + " environment variables");
    Map<String, String> out = new TreeMap<>();
    env.forEach((k, v) -> {
      if (k == null || !ENV_NAME.matcher(k).matches()) {
        throw new IllegalArgumentException("environment variable name " + k + ": letters, digits and _, not starting with a digit");
      }
      String val = v == null ? "" : v;
      if (val.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 8192) {
        throw new IllegalArgumentException("environment variable " + k + " is longer than 8 KiB");
      }
      out.put(k, val);
    });
    return out;
  }

  static Map<String, String> checkLabels(Map<String, String> labels) {
    if (labels.size() > MAX_LABELS) throw new IllegalArgumentException("at most " + MAX_LABELS + " labels");
    Map<String, String> out = new TreeMap<>();
    labels.forEach((k, v) -> {
      if (k == null || !LABEL_KEY.matcher(k).matches()) {
        throw new IllegalArgumentException("label key " + k + ": 1 to 63 of letters, digits, '.', '_', '/' or '-'");
      }
      String val = v == null ? "" : v;
      if (val.length() > 256) throw new IllegalArgumentException("label " + k + " is longer than 256 characters");
      out.put(k, val);
    });
    return out;
  }

  static String checkSecretName(String name) {
    if (name == null || !SECRET_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("secret name " + name + ": 1 to 32 of lowercase letters, digits and -");
    }
    return name;
  }

  /** env as K=V strings, sorted by name. */
  static java.util.List<String> envList(Map<String, String> env) {
    java.util.List<String> kv = new ArrayList<>();
    new TreeMap<>(env).forEach((k, v) -> kv.add(k + "=" + v));
    return kv;
  }

  /**
   * A change to a sandbox's lifetime or sleep timer:
   * {@code box.update(new CloudOptions.Update().ttlSeconds(7200))}, or
   * {@code new CloudOptions.Update().clearLifetime()} to stop it being deleted automatically.
   */
  public static final class Update {
    Integer ttlS;
    Long deleteAfter;
    Integer sleepAfterS;
    boolean clearLifetime;

    /** Delete it this many seconds from now, {@value CloudOptions#MIN_TTL_S} to {@value CloudOptions#MAX_TTL_S}. */
    public Update ttlSeconds(int v) { ttlS = v; return this; }
    /** Or: delete it at this Unix time in milliseconds. */
    public Update deleteAfter(long unixMs) { deleteAfter = unixMs; return this; }
    /**
     * Idle seconds before it sleeps (0 never). The new value is recorded; the host keeps the idle
     * time the sandbox started with until it can change it in place.
     */
    public Update sleepAfterS(int v) { sleepAfterS = v; return this; }
    /** Remove the automatic delete (sends {@code "ttl_s": null}). Not with ttlSeconds or deleteAfter. */
    public Update clearLifetime() { clearLifetime = true; return this; }

    Map<String, Object> body() {
      if (clearLifetime && (ttlS != null || deleteAfter != null)) {
        throw new IllegalArgumentException("clearLifetime cannot be combined with ttlSeconds or deleteAfter");
      }
      checkLifetime(ttlS, deleteAfter);
      checkSleepAfter(sleepAfterS);
      Map<String, Object> b = new LinkedHashMap<>();
      if (clearLifetime) b.put("ttl_s", null);
      if (ttlS != null) b.put("ttl_s", ttlS);
      if (deleteAfter != null) b.put("delete_after", deleteAfter);
      if (sleepAfterS != null) b.put("sleep_after_s", sleepAfterS);
      if (b.isEmpty()) throw new IllegalArgumentException("give ttlSeconds, deleteAfter, sleepAfterS or clearLifetime");
      return b;
    }
  }

  /** Which sandboxes to list: {@code new CloudOptions.List().all(true).limit(50)}. */
  public static final class List {
    boolean all;
    Integer limit;
    String cursor;
    String name;
    Map<String, String> labels;

    /** Only the sandbox with this name. */
    public List name(String v) { name = v; return this; }
    /** Only sandboxes with every one of these labels; an empty value matches the key with any value. */
    public List labels(Map<String, String> v) { labels = v; return this; }

    /** Deleted ones too. */
    public List all(boolean v) { all = v; return this; }
    /** At most this many per page (a server that does not page yet answers everything). */
    public List limit(int v) {
      if (v < 1) throw new IllegalArgumentException("limit must be 1 or more");
      limit = v;
      return this;
    }
    /** The page after this one: a {@link CloudSandboxPage#nextCursor()}. */
    public List cursor(String v) { cursor = v; return this; }

    List copy() {
      List c = new List();
      c.all = all;
      c.limit = limit;
      c.cursor = cursor;
      c.name = name;
      c.labels = labels;
      return c;
    }

    String query() {
      StringBuilder q = new StringBuilder();
      if (all) q.append("&all=1");
      if (limit != null) q.append("&limit=").append(limit);
      if (cursor != null && !cursor.isEmpty()) q.append("&cursor=").append(Transport.enc(cursor));
      if (name != null) q.append("&name=").append(Transport.enc(name));
      if (labels != null) {
        new TreeMap<>(labels).forEach((k, v) -> q.append("&label=").append(Transport.enc(v == null || v.isEmpty() ? k : k + "=" + v)));
      }
      return q.length() == 0 ? "" : "?" + q.substring(1);
    }
  }

  static void checkLifetime(Integer ttlS, Long deleteAfter) {
    if (ttlS != null && deleteAfter != null) throw new IllegalArgumentException("give ttlSeconds or deleteAfter, not both");
    if (ttlS != null && (ttlS < MIN_TTL_S || ttlS > MAX_TTL_S)) {
      throw new IllegalArgumentException("ttlSeconds must be from " + MIN_TTL_S + " to " + MAX_TTL_S + " (90 days), not " + ttlS);
    }
    if (deleteAfter != null && deleteAfter <= 0) throw new IllegalArgumentException("deleteAfter must be a Unix time in milliseconds");
  }

  // --- fan-out: batches, forks, leases, drains and the sealed grader -------------------------

  /** The shortest lease, in seconds. */
  public static final int MIN_LEASE_S = 60;
  /** The longest lease, in seconds (a day). */
  public static final int MAX_LEASE_S = 86_400;
  /** The most sandboxes one batch or one fork call makes. */
  public static final int MAX_FANOUT = 1000;
  /** The label kosa8 Cloud puts on every sandbox made with a runId. */
  public static final String RUN_LABEL = "kosa8.run";
  /** A sealed grade's hidden files: at most this many ... */
  public static final int GRADE_MAX_FILES = 64;
  /** ... adding up to at most this many bytes. */
  public static final int GRADE_MAX_BYTES = 8 * 1024 * 1024;

  static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}");
  static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,58}");
  static final Pattern SANDBOX_ID = Pattern.compile("[A-Za-z0-9_]{1,64}");

  static int checkLease(int v, String what) {
    if (v < MIN_LEASE_S || v > MAX_LEASE_S) throw new IllegalArgumentException(what + " must be from " + MIN_LEASE_S + " to " + MAX_LEASE_S + " seconds, not " + v);
    return v;
  }

  static String checkRunId(String v) {
    if (v == null || !RUN_ID.matcher(v).matches()) throw new IllegalArgumentException("runId: 1 to 128 of letters, digits, '_', '.', ':' or '-', starting with a letter or digit, not " + v);
    return v;
  }

  /** The fields a batch and a fork share. */
  abstract static class Fanout<S extends Fanout<S>> {
    Map<String, String> labels;
    String runId;
    Integer leaseS;
    String prefix;
    Boolean partialOk;
    String idempotencyKey;

    @SuppressWarnings("unchecked")
    private S self() { return (S) this; }

    /** Labels for every sandbox made (at most 16, kosa8.run included). */
    public S labels(Map<String, String> v) { labels = v; return self(); }
    /** Labels each kosa8.run=runId, which heartbeat, drain and an RL run select by. */
    public S runId(String v) { runId = checkRunId(v); return self(); }
    /** Leases each: deleted leaseS seconds from now unless a heartbeat extends it. */
    public S leaseS(int v) { leaseS = checkLease(v, "leaseS"); return self(); }
    /** Names them prefix-0001, prefix-0002, ... */
    public S prefix(String v) {
      if (v == null || !PREFIX.matcher(v).matches()) throw new IllegalArgumentException("prefix: 1 to 59 of letters, digits, '.', '_' or '-', not " + v);
      prefix = v;
      return self();
    }
    /** true: make what fits and list the rest in failed; false: all or nothing. */
    public S partialOk(boolean v) { partialOk = v; return self(); }
    /** The Idempotency-Key (default a new UUID, renewed after a 5xx), sent unchanged on every retry when yours. */
    public S idempotencyKey(String v) { idempotencyKey = Kosa8Cloud.checkIdempotencyKey(v); return self(); }

    void put(Map<String, Object> b) {
      if (labels != null) {
        Map<String, String> l = checkLabels(labels);
        if (l.size() + (runId != null && !l.containsKey(RUN_LABEL) ? 1 : 0) > MAX_LABELS) {
          throw new IllegalArgumentException("at most " + MAX_LABELS + " labels, " + RUN_LABEL + " included");
        }
        b.put("labels", l);
      }
      if (runId != null) b.put("run_id", runId);
      if (leaseS != null) b.put("lease_s", leaseS);
      if (prefix != null) b.put("prefix", prefix);
      if (partialOk != null) b.put("partial_ok", partialOk);
    }
  }

  /**
   * Many sandboxes in one call: {@code cloud.sandboxes().createMany(100, new CloudOptions.Batch()
   * .create(new CloudOptions.Create().template("python")).runId("run-1").leaseS(600))}. The create's fields
   * (template, size, image, env, ...; not name) apply to every one; prefix names them.
   */
  public static final class Batch extends Fanout<Batch> {
    Create create;

    /** The create fields every sandbox gets (not name: use prefix). */
    public Batch create(Create v) { create = v; return this; }
    /** Shorthand for {@code create(new Create().template(v))}. */
    public Batch template(String v) { create = (create == null ? new Create() : create).template(v); return this; }

    Map<String, Object> body(int count) {
      if (count < 1 || count > MAX_FANOUT) throw new IllegalArgumentException("count must be from 1 to " + MAX_FANOUT + ", not " + count);
      Create c = create == null ? new Create() : create;
      if (c.name != null) throw new IllegalArgumentException("a batch names its sandboxes with prefix, not name");
      if (c.leaseS != null) throw new IllegalArgumentException("set leaseS on the Batch, not its Create");
      if (leaseS != null && (c.ttlS != null || c.deleteAfter != null)) throw new IllegalArgumentException("give leaseS or ttlSeconds/deleteAfter, not both");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("count", count);
      Map<String, Object> cb = c.body();
      if (labels != null && cb.containsKey("labels")) throw new IllegalArgumentException("give labels on the Batch or its Create, not both");
      b.putAll(cb);
      put(b);
      return b;
    }
  }

  /** A fork's options: {@code box.fork(new CloudOptions.Fork().n(8).runId("run-1").leaseS(600))}. */
  public static final class Fork extends Fanout<Fork> {
    int n = 1;

    /** How many copies, 1 to {@value CloudOptions#MAX_FANOUT} (default 1). */
    public Fork n(int v) {
      if (v < 1 || v > MAX_FANOUT) throw new IllegalArgumentException("n must be from 1 to " + MAX_FANOUT + ", not " + v);
      n = v;
      return this;
    }

    Map<String, Object> body() {
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("n", n);
      put(b);
      return b;
    }
  }

  /** Which sandboxes a heartbeat or a drain is for: a run, labels, ids (at least one). */
  public static final class Selector {
    String runId;
    Map<String, String> labels;
    java.util.List<String> ids;

    /** Sandboxes labelled kosa8.run=runId. */
    public static Selector run(String runId) { return new Selector().runId(runId); }
    /** These sandboxes. */
    public static Selector ids(java.util.List<String> ids) { return new Selector().idList(ids); }

    public Selector runId(String v) { runId = checkRunId(v); return this; }
    /** Sandboxes carrying every one of these labels. */
    public Selector labels(Map<String, String> v) { labels = v; return this; }
    /** At most 1000 sandbox ids. */
    public Selector idList(java.util.List<String> v) {
      if (v == null || v.isEmpty() || v.size() > MAX_FANOUT) throw new IllegalArgumentException("ids must be 1 to " + MAX_FANOUT + " sandbox ids");
      for (String id : v) if (id == null || !SANDBOX_ID.matcher(id).matches()) throw new IllegalArgumentException("not a sandbox id: " + id);
      ids = java.util.List.copyOf(v);
      return this;
    }

    Map<String, Object> body() {
      Map<String, Object> b = new LinkedHashMap<>();
      if (runId != null) b.put("run_id", runId);
      if (labels != null && !labels.isEmpty()) b.put("labels", checkLabels(labels));
      if (ids != null) b.put("ids", ids);
      if (b.isEmpty()) throw new IllegalArgumentException("give runId, labels or ids: which sandboxes");
      return b;
    }
  }

  /**
   * A sealed grade: {@code box.grade(List.of("pytest", "-q", "/grader/test.py"), new CloudOptions.Grade()
   * .file("/grader/test.py", bytes).timeoutS(60))}. The hidden files go only into the grader's copy.
   */
  public static final class Grade {
    final Map<String, byte[]> files = new LinkedHashMap<>();
    Integer timeoutS;
    Map<String, String> env;
    boolean keep;
    String runId;
    Map<String, Object> rollout;
    String idempotencyKey;

    /** A hidden file at an absolute path. */
    public Grade file(String path, byte[] content) {
      if (path == null || !path.startsWith("/") || java.util.Arrays.asList(path.split("/")).contains("..") || path.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("a grader file path must be absolute, without .., not " + path);
      }
      files.put(path, content == null ? new byte[0] : content);
      return this;
    }
    /** A hidden text file (UTF-8). */
    public Grade file(String path, String content) { return file(path, content == null ? new byte[0] : content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    /** Hidden files by absolute path. */
    public Grade files(Map<String, byte[]> v) { if (v != null) v.forEach(this::file); return this; }
    /** 1 to 90 seconds. */
    public Grade timeoutS(int v) {
      if (v < 1 || v > 90) throw new IllegalArgumentException("timeoutS must be from 1 to 90, not " + v);
      timeoutS = v;
      return this;
    }
    public Grade env(Map<String, String> v) { env = v; return this; }
    /** Keep the grader's copy (leased for an hour) to look at; default it is deleted. */
    public Grade keep(boolean v) { keep = v; return this; }
    /** Record the result as a rollout of this RL run. */
    public Grade runId(String v) { runId = checkRunId(v); return this; }
    /** The rollout's step, group, index and meta (each may be null), recorded with runId. */
    public Grade rollout(Integer step, String group, Integer index, Map<String, Object> meta) {
      Map<String, Object> r = new LinkedHashMap<>();
      if (step != null) r.put("step", step);
      if (group != null) r.put("group", group);
      if (index != null) r.put("index", index);
      if (meta != null && !meta.isEmpty()) r.put("meta", meta);
      rollout = r;
      return this;
    }
    /**
     * The Idempotency-Key (default a new UUID, renewed after a 5xx), reused after a timeout: a retried grade records
     * nothing twice. Yours is sent unchanged on every retry.
     */
    public Grade idempotencyKey(String v) { idempotencyKey = Kosa8Cloud.checkIdempotencyKey(v); return this; }

    Map<String, Object> body(java.util.List<String> cmd) {
      if (cmd == null || cmd.isEmpty()) throw new IllegalArgumentException("grade needs a command");
      if (files.size() > GRADE_MAX_FILES) throw new IllegalArgumentException("at most " + GRADE_MAX_FILES + " grader files");
      long total = 0;
      Map<String, Object> fb = new LinkedHashMap<>();
      for (Map.Entry<String, byte[]> e : files.entrySet()) {
        total += e.getValue().length;
        fb.put(e.getKey(), java.util.Base64.getEncoder().encodeToString(e.getValue()));
      }
      if (total > GRADE_MAX_BYTES) throw new IllegalArgumentException("grader files add up to more than 8 MiB");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("cmd", java.util.List.copyOf(cmd));
      if (!fb.isEmpty()) b.put("files", fb);
      if (timeoutS != null) b.put("timeout_s", timeoutS);
      if (env != null && !env.isEmpty()) b.put("env", envList(checkEnv(env)));
      if (keep) b.put("keep", true);
      if (runId != null) b.put("run_id", runId);
      if (rollout != null && !rollout.isEmpty()) b.put("rollout", rollout);
      return b;
    }

    Grade copy() {
      Grade g = new Grade();
      g.files.putAll(files);
      g.timeoutS = timeoutS;
      g.env = env;
      g.keep = keep;
      g.runId = runId;
      g.rollout = rollout;
      return g;
    }
  }

  /** How {@link Kosa8Cloud#forkMap} runs. */
  public static final class ForkMap {
    int concurrency = 16;
    int retries = 2;
    java.time.Duration timeout;
    Map<String, String> labels;
    String runId;
    Integer leaseS;
    RetryPolicy policy;

    /** At most this many items at once (default 16). */
    public ForkMap concurrency(int v) {
      if (v < 1) throw new IllegalArgumentException("concurrency must be at least 1");
      concurrency = v;
      return this;
    }
    /** Tries again on a fresh fork this many times after a retryable failure (default 2). */
    public ForkMap retries(int v) {
      if (v < 0) throw new IllegalArgumentException("retries must be 0 or more");
      retries = v;
      return this;
    }
    /** Gives up on an item after this long; its sandbox is deleted (failure class timeout). */
    public ForkMap timeout(java.time.Duration v) { timeout = v; return this; }
    public ForkMap labels(Map<String, String> v) { labels = v; return this; }
    public ForkMap runId(String v) { runId = checkRunId(v); return this; }
    /** Leases each child, so a crashed process leaks none past leaseS. */
    public ForkMap leaseS(int v) { leaseS = checkLease(v, "leaseS"); return this; }
    /** The backoff between tries (default {@link RetryPolicy#DEFAULT}'s, with retries + 1 attempts). */
    public ForkMap policy(RetryPolicy v) { policy = v; return this; }
  }

  // --- org templates and capability tokens -------------------------------------------------

  static final Pattern ORG_TEMPLATE_NAME = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");

  static String checkTemplateName(String v) {
    if (v == null || !ORG_TEMPLATE_NAME.matcher(v).matches()) throw new IllegalArgumentException("a template name is 1 to 63 of lower-case letters, digits, '.', '_' or '-', not " + v);
    return v;
  }

  /** The size and setup shared by a template build and an import. */
  abstract static class TemplateSize<S extends TemplateSize<S>> {
    String size;
    Integer cpus;
    Integer memMb;
    Object setupCmd;
    String region;

    @SuppressWarnings("unchecked")
    private S self() { return (S) this; }

    /** One of {@link CloudOptions#SIZES}. */
    public S size(String v) {
      if (!SIZES.contains(v)) throw new IllegalArgumentException("size must be one of " + SIZES + ", not " + v);
      size = v;
      return self();
    }
    public S cpus(int cpus, int memMb) { this.cpus = cpus; this.memMb = memMb; return self(); }
    /** Run once in the booted template before it is saved. */
    public S setupCmd(java.util.List<String> v) { setupCmd = java.util.List.copyOf(v); return self(); }
    /** Run once under sh -c before it is saved. */
    public S setupCmd(String v) { setupCmd = v; return self(); }
    public S region(String v) { region = v; return self(); }

    void put(Map<String, Object> b) {
      if (size != null && cpus != null) throw new IllegalArgumentException("give size or cpus and memMb, not both");
      if (size != null) b.put("size", size);
      if (cpus != null) { b.put("cpus", cpus); b.put("mem_mb", memMb); }
      if (setupCmd != null) b.put("setup_cmd", setupCmd);
      if (region != null) b.put("region", region);
    }
  }

  /** An org template build: from an image (tag or @sha256 digest) or a Dockerfile's text, one of them. */
  public static final class TemplateBuild extends TemplateSize<TemplateBuild> {
    String image;
    String dockerfile;
    Map<String, String> buildArgs;

    public TemplateBuild image(String v) { image = v; return this; }
    /** The Dockerfile's text; there is no build context (COPY/ADD only --from= a stage or a URL). */
    public TemplateBuild dockerfile(String v) { dockerfile = v; return this; }
    /** For a Dockerfile build. */
    public TemplateBuild buildArgs(Map<String, String> v) { buildArgs = v; return this; }

    Map<String, Object> body(String name) {
      if ((image == null) == (dockerfile == null)) throw new IllegalArgumentException("give image or dockerfile, one of them");
      if (buildArgs != null && dockerfile == null) throw new IllegalArgumentException("buildArgs are for a dockerfile build");
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("name", checkTemplateName(name));
      if (image != null) b.put("image", image);
      if (dockerfile != null) b.put("dockerfile", dockerfile);
      if (buildArgs != null) b.put("build_args", new TreeMap<>(buildArgs));
      put(b);
      return b;
    }
  }

  /** Options for importing many image templates at once. */
  public static final class TemplateImport extends TemplateSize<TemplateImport> {
    Boolean partialOk;

    /** Queue what can be queued and list the rest in failed, instead of refusing the call. */
    public TemplateImport partialOk(boolean v) { partialOk = v; return this; }

    Map<String, Object> body(java.util.List<Map<String, String>> images) {
      if (images == null || images.isEmpty() || images.size() > MAX_FANOUT) throw new IllegalArgumentException("images must be 1 to " + MAX_FANOUT + " {name, image}");
      java.util.List<Map<String, String>> list = new ArrayList<>();
      for (Map<String, String> m : images) {
        if (m == null || m.get("image") == null) throw new IllegalArgumentException("each image is {name, image}");
        Map<String, String> x = new LinkedHashMap<>();
        x.put("name", checkTemplateName(m.get("name")));
        x.put("image", m.get("image"));
        list.add(x);
      }
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("images", list);
      put(b);
      if (partialOk != null) b.put("partial_ok", partialOk);
      return b;
    }
  }

  /** The scopes a capability token may hold. */
  public static final java.util.List<String> TOKEN_SCOPES = java.util.List.of("sandboxes:read", "sandboxes:write", "functions:invoke");

  /** A capability token's limits. */
  public static final class Token {
    java.util.List<String> sandboxIds;
    Map<String, String> labels;
    Integer ttlS;
    String name;

    /** Only these sandboxes (1 to 100, sbx_...). */
    public Token sandboxIds(java.util.List<String> v) {
      if (v == null || v.isEmpty() || v.size() > 100) throw new IllegalArgumentException("sandboxIds must be 1 to 100 sandbox ids");
      sandboxIds = java.util.List.copyOf(v);
      return this;
    }
    /** Only sandboxes carrying every label; a sandbox it creates gets them. */
    public Token labels(Map<String, String> v) { labels = v; return this; }
    /** Lifetime, 60 s to 24 h (default one hour). */
    public Token ttlS(int v) {
      if (v < 60 || v > 86_400) throw new IllegalArgumentException("ttlS must be from 60 to 86400, not " + v);
      ttlS = v;
      return this;
    }
    public Token name(String v) {
      if (v != null && v.length() > 64) throw new IllegalArgumentException("a token name is at most 64 characters");
      name = v;
      return this;
    }

    Map<String, Object> body(java.util.List<String> scopes) {
      if (scopes == null || scopes.isEmpty() || new java.util.HashSet<>(scopes).size() != scopes.size() || !TOKEN_SCOPES.containsAll(scopes)) {
        throw new IllegalArgumentException("scopes must be a non-empty list from " + TOKEN_SCOPES);
      }
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("scopes", java.util.List.copyOf(scopes));
      if (sandboxIds != null) b.put("sandbox_ids", sandboxIds);
      if (labels != null) b.put("labels", checkLabels(labels));
      if (ttlS != null) b.put("ttl_s", ttlS);
      if (name != null) b.put("name", name);
      return b;
    }
  }

  static void checkSleepAfter(Integer s) {
    if (s != null && (s < 0 || s > MAX_SLEEP_AFTER_S)) {
      throw new IllegalArgumentException("sleepAfterS must be from 0 (never) to " + MAX_SLEEP_AFTER_S + ", not " + s);
    }
  }
}
