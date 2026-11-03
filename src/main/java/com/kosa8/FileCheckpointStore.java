package com.kosa8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * The store the kosa8 CLI uses: ~/.kosa8/checkpoints.json (0600, rewritten atomically), so
 * {@code kosa8 rewind} sees checkpoints taken here and the other way round.
 *
 * <p>Updates take an exclusive lock on checkpoints.json.lock. Java can only take fcntl locks,
 * while the CLI takes flock locks; on Linux these do not exclude each other, so a Java update
 * racing a CLI checkpoint at the same instant can lose one record. Java processes exclude each
 * other, and the atomic rename means no reader ever sees a partial file.
 */
public final class FileCheckpointStore implements CheckpointStore {
  private final Path path;

  public FileCheckpointStore() {
    this(Path.of(System.getProperty("user.home"), ".kosa8", "checkpoints.json"));
  }

  public FileCheckpointStore(Path path) { this(path, java.time.Duration.ofSeconds(10)); }

  /** lockTimeout: how long to wait for checkpoints.json.lock (10 s by default, as @kosa8/sdk). */
  public FileCheckpointStore(Path path, java.time.Duration lockTimeout) {
    this.path = path;
    this.lockTimeout = lockTimeout;
  }

  private final java.time.Duration lockTimeout;

  // A JVM may hold only one FileLock per file: a second FileCheckpointStore on the
  // same path (two clients) would get OverlappingFileLockException. Every store
  // on a path serialises on one in-process lock before taking the file lock.
  private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

  public Path path() { return path; }

  @Override
  @SuppressWarnings("unchecked")
  public Map<String, List<Checkpoint>> load() {
    String text;
    try {
      text = Files.readString(path, StandardCharsets.UTF_8);
    } catch (NoSuchFileException e) {
      return new HashMap<>();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    Map<String, List<Checkpoint>> out = new HashMap<>();
    try {
      Object o = Json.parse(text);
      if (!(o instanceof Map<?, ?> m)) return out;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        List<Checkpoint> list = new ArrayList<>();
        if (e.getValue() instanceof List<?> l) {
          for (Object x : l) {
            if (x instanceof Map<?, ?> c) list.add(fromJson((Map<String, Object>) c));
          }
        }
        out.put(String.valueOf(e.getKey()), list);
      }
    } catch (RuntimeException e) {
      return new HashMap<>(); // a corrupt store must not make the sandbox unusable
    }
    return out;
  }

  static Checkpoint fromJson(Map<String, Object> c) {
    Instant created;
    try {
      created = OffsetDateTime.parse(String.valueOf(c.get("created"))).toInstant(); // Go writes local offsets and nanoseconds
    } catch (RuntimeException e) {
      created = Instant.EPOCH;
    }
    return new Checkpoint(str(c.get("id")), str(c.get("sandbox")), str(c.get("label")),
        Boolean.TRUE.equals(c.get("auto")), created);
  }

  static Map<String, Object> toJson(Checkpoint c) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", c.id());
    m.put("sandbox", c.sandbox());
    m.put("label", c.label() == null ? "" : c.label());
    if (c.auto()) m.put("auto", true); // omitted when false, as the CLI writes it
    m.put("created", c.created().toString());
    return m;
  }

  private static String str(Object o) { return o == null ? "" : o.toString(); }

  @Override
  public <T> T update(Function<Map<String, List<Checkpoint>>, T> fn) {
    long deadline = System.nanoTime() + lockTimeout.toNanos();
    ReentrantLock jvm = JVM_LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), k -> new ReentrantLock());
    try {
      if (!jvm.tryLock(lockTimeout.toNanos(), TimeUnit.NANOSECONDS)) throw lockTimedOut();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Kosa8Exception.CheckpointException("interrupted waiting for " + path + ".lock", e);
    }
    try {
      Files.createDirectories(path.getParent());
      try (FileChannel lock = FileChannel.open(path.resolveSibling(path.getFileName() + ".lock"),
              StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock ignored = acquire(lock, deadline)) {
        Map<String, List<Checkpoint>> d = load();
        T r = fn.apply(d);
        Map<String, Object> out = new LinkedHashMap<>();
        d.forEach((k, v) -> out.put(k, v.stream().map(FileCheckpointStore::toJson).toList()));
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, Json.write(out), StandardCharsets.UTF_8);
        try {
          Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
          // not a POSIX filesystem
        }
        try {
          Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
          Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return r;
      }
    } catch (IOException e) {
      throw new Kosa8Exception.CheckpointException("update the checkpoint store " + path + ": " + e.getMessage(), e);
    } finally {
      jvm.unlock();
    }
  }

  private FileLock acquire(FileChannel ch, long deadline) throws IOException {
    while (true) {
      FileLock l;
      try {
        l = ch.tryLock();
      } catch (OverlappingFileLockException e) {
        l = null; // held elsewhere in this JVM outside a FileCheckpointStore
      }
      if (l != null) return l;
      if (System.nanoTime() >= deadline) throw lockTimedOut();
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new Kosa8Exception.CheckpointException("interrupted waiting for " + path + ".lock", e);
      }
    }
  }

  private Kosa8Exception.CheckpointException lockTimedOut() {
    return new Kosa8Exception.CheckpointException("timed out after " + lockTimeout.toSeconds() + "s waiting for "
        + path + ".lock; another kosa8 process is holding it");
  }
}
