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
    Integer cpus;
    Integer memMb;
    String image;
    Integer diskGb;
    Boolean persistent;
    Boolean docker;
    String cpu;
    String name;
    Map<String, String> labels;
    Map<String, String> env;
    java.util.List<String> secrets;
    Map<String, Object> egress;
    String snapshot;
    boolean noWait;

    /**
     * vCPU x GiB: one of {@link CloudOptions#SIZES}. A size, cpus+memMb, image or diskGb boots the
     * sandbox cold at that size; create waits for it unless {@link #noWait()}.
     */
    public Create size(String v) { size = v; return this; }
    /** Or: a size by its parts (4 and 8192 for 4x8). */
    public Create cpus(int cpus, int memMb) { this.cpus = cpus; this.memMb = memMb; return this; }
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
      if (acceptPrice != null) b.put("accept_price", acceptPrice);
      if (size != null) {
        if (!SIZES.contains(size)) throw new IllegalArgumentException("size must be one of " + SIZES + ", not " + size);
        b.put("size", size);
      }
      if (cpus != null) {
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

  static void checkSleepAfter(Integer s) {
    if (s != null && (s < 0 || s > MAX_SLEEP_AFTER_S)) {
      throw new IllegalArgumentException("sleepAfterS must be from 0 (never) to " + MAX_SLEEP_AFTER_S + ", not " + s);
    }
  }
}
