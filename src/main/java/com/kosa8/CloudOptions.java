package com.kosa8;

import java.util.LinkedHashMap;
import java.util.Map;

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

  /** A new cloud sandbox: {@code cloud.sandboxes().create(new CloudOptions.Create().template("python").ttlSeconds(3600))}. */
  public static final class Create {
    String template;
    String region;
    Integer sleepAfterS;
    Integer ttlS;
    Long deleteAfter;
    Double acceptPrice;

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
      return b;
    }
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
      return c;
    }

    String query() {
      StringBuilder q = new StringBuilder();
      if (all) q.append("&all=1");
      if (limit != null) q.append("&limit=").append(limit);
      if (cursor != null && !cursor.isEmpty()) q.append("&cursor=").append(Transport.enc(cursor));
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
