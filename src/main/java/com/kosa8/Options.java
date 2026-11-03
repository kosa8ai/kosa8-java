package com.kosa8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Per-call option objects. Every setter returns {@code this}; unset fields take the daemon's defaults. */
public final class Options {
  private Options() {}

  /** For a call that a policy may gate: a signed approval. */
  public static class Call<T extends Call<T>> {
    String approval;

    @SuppressWarnings("unchecked")
    public T approval(String a) { approval = a; return (T) this; }
  }

  /** A bare call with only an approval. */
  public static final class Approval extends Call<Approval> {
    public static Approval of(String sig) { return new Approval().approval(sig); }
  }

  /** A new sandbox. */
  public static final class Create extends Call<Create> {
    String name;
    Integer cpus;
    Integer memMb;
    Integer diskGb;
    List<String> mounts;
    String egress;
    List<String> egressAllow;
    String capturePath;
    boolean captureBool;
    Map<String, String> labels;
    Object broker;

    public Create name(String v) { name = v; return this; }
    public Create cpus(int v) { cpus = v; return this; }
    public Create memMb(int v) { memMb = v; return this; }
    public Create diskGb(int v) { diskGb = v; return this; }
    /** host:guest[:ro] */
    public Create mounts(List<String> v) { mounts = v; return this; }
    /** deny | any | fakenet | allow:host,host */
    public Create egress(String v) { egress = v; return this; }
    /** Limit the network to these hosts (sets egress to allow:…). */
    public Create egressAllow(List<String> v) { egressAllow = v; return this; }
    /**
     * Record the sandbox's network traffic to this host file (daemon 0.9.36 writes one pcapng file there).
     */
    public Create capture(String hostFile) { capturePath = hostFile; return this; }
    /**
     * @deprecated the daemon needs a file to write to; use {@link #capture(String)}.
     *     {@code capture(true)} now fails before the request instead of with a 500.
     */
    @Deprecated
    public Create capture(boolean v) { captureBool = v; return this; }
    public Create labels(Map<String, String> v) { labels = v; return this; }
    /** The credential-broker config, passed through. */
    public Create broker(Object v) { broker = v; return this; }

    Map<String, Object> body() {
      Map<String, Object> b = new LinkedHashMap<>();
      if (name != null) b.put("name", name);
      if (cpus != null) b.put("cpus", cpus);
      if (memMb != null) b.put("mem_mb", memMb);
      if (diskGb != null) b.put("disk_gb", diskGb);
      if (mounts != null) {
        List<Map<String, Object>> ms = new ArrayList<>();
        for (String m : mounts) {
          String[] p = m.split(":", -1);
          boolean ro = false;
          if (p.length == 3 && (p[2].equals("ro") || p[2].equals("rw"))) {
            ro = p[2].equals("ro");
            p = new String[] {p[0], p[1]};
          }
          if (p.length != 2 || p[0].isEmpty() || p[1].isEmpty())
            throw new Kosa8Exception("mount \"" + m + "\": want host:guest or host:guest:ro");
          Map<String, Object> o = new LinkedHashMap<>();
          o.put("host_path", p[0]);
          o.put("guest_path", p[1]);
          o.put("read_only", ro);
          ms.add(o);
        }
        b.put("mounts", ms);
      }
      if (egressAllow != null && !egressAllow.isEmpty()) {
        if (egress != null && !egress.equals("deny"))
          throw new Kosa8Exception("egressAllow already limits the network to the hosts it lists; do not also set egress");
        List<String> hosts = new ArrayList<>();
        for (String h : egressAllow) hosts.add(h.strip());
        b.put("egress", "allow:" + String.join(",", hosts));
      } else if (egress != null) {
        b.put("egress", egress);
      }
      if (capturePath != null) {
        if (capturePath.isEmpty()) throw new Kosa8Exception("capture needs a host file path to write the capture to");
        b.put("capture", Map.of("path", capturePath));
      } else if (captureBool) {
        throw new Kosa8Exception("capture(true) is not enough: the daemon needs a host file to write "
            + "the capture to; use capture(\"/path/to/capture.pcapng\")");
      }
      if (labels != null) b.put("labels", labels);
      if (broker != null) b.put("broker", broker);
      return b;
    }
  }

  /** DeleteSandbox. */
  public static final class Delete extends Call<Delete> {
    boolean wipe;
    boolean keepCheckpoints;

    /** Overwrite the disks, not just unlink them. */
    public Delete wipe(boolean v) { wipe = v; return this; }
    /** Keep this sandbox's checkpoint snapshots. */
    public Delete keepCheckpoints(boolean v) { keepCheckpoints = v; return this; }
  }

  /** Run, exec and attach. Callbacks see output as it arrives. */
  public static final class Run extends Call<Run> {
    List<String> env;
    Consumer<byte[]> onStdout;
    Consumer<byte[]> onStderr;
    Consumer<byte[]> onStatus;
    Duration timeout;

    /** KEY=VALUE */
    public Run env(List<String> v) { env = v; return this; }
    public Run env(Map<String, String> v) {
      List<String> l = new ArrayList<>();
      v.forEach((k, x) -> l.add(k + "=" + x));
      env = l;
      return this;
    }
    public Run onStdout(Consumer<byte[]> v) { onStdout = v; return this; }
    public Run onStderr(Consumer<byte[]> v) { onStderr = v; return this; }
    /** Pull and progress lines. */
    public Run onStatus(Consumer<byte[]> v) { onStatus = v; return this; }
    public Run timeout(Duration v) { timeout = v; return this; }
  }

  /** StartInteractive. */
  public static final class Interactive extends Call<Interactive> {
    List<String> env;
    boolean tty;

    public Interactive env(List<String> v) { env = v; return this; }
    public Interactive tty(boolean v) { tty = v; return this; }
  }

  /** Snapshot. */
  public static final class Snapshot extends Call<Snapshot> {
    String id;
    boolean encrypt;

    public Snapshot id(String v) { id = v; return this; }
    public Snapshot encrypt(boolean v) { encrypt = v; return this; }
  }

  /** RestoreSnapshot. */
  public static final class Restore extends Call<Restore> {
    String name;
    String label;
    boolean allowTampered;

    public Restore name(String v) { name = v; return this; }
    public Restore label(String v) { label = v; return this; }
    public Restore allowTampered(boolean v) { allowTampered = v; return this; }
  }

  /** DeleteSnapshot. */
  public static final class SnapshotDelete extends Call<SnapshotDelete> {
    boolean wipe;
    boolean force;

    public SnapshotDelete wipe(boolean v) { wipe = v; return this; }
    public SnapshotDelete force(boolean v) { force = v; return this; }
  }

  /** Fork. */
  public static final class Fork extends Call<Fork> {
    String prefix;
    String label;

    public Fork prefix(String v) { prefix = v; return this; }
    public Fork label(String v) { label = v; return this; }
  }

  /** Explore: best-of-N. */
  public static final class Explore extends Call<Explore> {
    String image = "alpine:latest";
    boolean keepAll;
    String keep;
    Duration timeout = Duration.ofSeconds(120);
    String prefix;
    boolean pull = true;

    public Explore image(String v) { image = v; return this; }
    /** Keep every branch. */
    public Explore keepAll(boolean v) { keepAll = v; return this; }
    /** Keep this branch instead of the fastest success. */
    public Explore keep(String v) { keep = v; return this; }
    /** Per branch. */
    public Explore timeout(Duration v) { timeout = v; return this; }
    public Explore prefix(String v) { prefix = v; return this; }
    /** False when the image is already in the sandbox. */
    public Explore pull(boolean v) { pull = v; return this; }
  }
}
