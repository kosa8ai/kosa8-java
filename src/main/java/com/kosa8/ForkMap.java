package com.kosa8;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine behind {@link Kosa8Cloud#forkMap} and {@link Run#map}: hand out forks of a base sandbox, run work
 * in them, retry what can pass, and never leave a child behind.
 */
public final class ForkMap {
  private ForkMap() {}

  /** Work for one item, in its own child sandbox. Anything it throws is reported in the item's result. */
  @FunctionalInterface
  public interface Fn<T, R> {
    R apply(CloudSandbox child, T item) throws Exception;
  }

  /** Children forked ahead of the workers in one call, at most. */
  static final int PREFORK_MAX = 100;

  static Kosa8Exception classified(String message, String failureClass, boolean retryable) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", failureClass);
    body.put("message", message);
    body.put("failure_class", failureClass);
    body.put("retryable", retryable);
    return new Kosa8Exception(message, 0, message, null, null, null, body, null);
  }

  static Kosa8Exception cancelled() { return classified("cancelled before it ran", "error", false); }

  static ThreadFactory daemons(String name) {
    AtomicInteger n = new AtomicInteger();
    return r -> {
      Thread t = new Thread(r, name + "-" + n.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }

  /** Forks of base for workers to take. Every child made and not released is tracked, so close deletes them all. */
  static final class ChildPool {
    final Kosa8Cloud cloud;
    final CloudSandbox base;
    final Map<String, String> labels;
    final String runId;
    final Integer leaseS;
    final ConcurrentLinkedQueue<CloudSandbox> ready = new ConcurrentLinkedQueue<>();
    final Map<String, CloudSandbox> live = new ConcurrentHashMap<>();
    volatile boolean closed;

    ChildPool(Kosa8Cloud cloud, CloudSandbox base, Map<String, String> labels, String runId, Integer leaseS) {
      if (base == null) throw new IllegalArgumentException("give a base sandbox to fork");
      this.cloud = cloud;
      this.base = base;
      this.labels = labels;
      this.runId = runId;
      this.leaseS = leaseS;
    }

    List<CloudSandbox> fork(int n, Boolean partialOk) {
      CloudOptions.Fork f = new CloudOptions.Fork().n(n);
      if (labels != null) f.labels(labels);
      if (runId != null) f.runId(runId);
      if (leaseS != null) f.leaseS(leaseS);
      if (partialOk != null) f.partialOk(partialOk);
      SandboxBatch b = base.fork(f);
      for (CloudSandbox k : b.sandboxes()) live.put(k.id(), k);
      if (closed) {
        for (CloudSandbox k : b.sandboxes()) release(k);
        throw cancelled();
      }
      if (b.sandboxes().isEmpty()) {
        throw b.failed().isEmpty() ? classified("the fork made no sandbox", "infra", true) : b.failed().get(0).toException();
      }
      return b.sandboxes();
    }

    /** Fork up to n ahead; failures are ignored (each worker then forks its own and sees the error). */
    void prefork(int n) {
      int left = n;
      while (left > 0 && !closed) {
        int k = Math.min(left, PREFORK_MAX);
        try {
          ready.addAll(fork(k, true));
        } catch (RuntimeException e) {
          return;
        }
        left -= k;
      }
    }

    CloudSandbox take() {
      if (closed) throw cancelled();
      CloudSandbox c = ready.poll();
      return c != null ? c : fork(1, null).get(0);
    }

    /** Delete a child taken from the pool (once); one that could not be deleted is kept for close. */
    void release(CloudSandbox child) {
      if (child != null && live.remove(child.id()) != null && !tryDelete(child)) live.put(child.id(), child);
    }

    /** Stop tracking a child without deleting it (keep). */
    void forget(CloudSandbox child) { if (child != null) live.remove(child.id()); }

    /** Delete every child not yet released (queued or in use): one drain, then one by one for what it missed. */
    void close() {
      closed = true;
      ready.clear();
      List<CloudSandbox> kids = new ArrayList<>(live.values());
      live.clear();
      if (kids.isEmpty()) return;
      try {
        for (int i = 0; i < kids.size(); i += CloudOptions.MAX_FANOUT) {
          List<String> ids = new ArrayList<>();
          for (CloudSandbox k : kids.subList(i, Math.min(kids.size(), i + CloudOptions.MAX_FANOUT))) ids.add(k.id());
          cloud.sandboxes().drain(CloudOptions.Selector.ids(ids));
        }
        return;
      } catch (RuntimeException e) {
        // one by one below
      }
      for (CloudSandbox k : kids) quietDelete(k);
    }
  }

  static void quietDelete(CloudSandbox box) { tryDelete(box); }

  /** Delete it; true when it is gone (a 404 included). */
  static boolean tryDelete(CloudSandbox box) {
    try {
      box.delete();
      return true;
    } catch (Kosa8Exception.NotFoundException gone) {
      return true;
    } catch (RuntimeException e) {
      return false; // its lease or the map's close deletes it
    }
  }

  /** fn(child, item), cut off after timeout (null: none): the child is then deleted and a timeout thrown. */
  static <T, R> R call(Fn<T, R> fn, CloudSandbox child, T item, Duration timeout, ChildPool pool) throws Exception {
    if (timeout == null) return fn.apply(child, item);
    ExecutorService one = Executors.newSingleThreadExecutor(daemons("kosa8-item"));
    try {
      Future<R> f = one.submit(() -> fn.apply(child, item));
      try {
        return f.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
      } catch (java.util.concurrent.TimeoutException e) {
        f.cancel(true);
        pool.release(child);
        throw classified("gave up after " + timeout.toMillis() / 1000.0 + " s; its sandbox was deleted", "timeout", false);
      } catch (ExecutionException e) {
        throw e.getCause() instanceof Exception x ? x : e;
      }
    } finally {
      one.shutdownNow();
    }
  }

  /**
   * worker(i) for i in [0, n) on concurrency threads, results in order. On an interrupt, stop is set, queued
   * items are dropped and cleanup runs; cleanup always runs.
   */
  static <X> List<X> runPool(int n, java.util.function.IntFunction<X> worker, int concurrency, AtomicBoolean stop, Runnable cleanup) {
    List<X> out = new ArrayList<>();
    if (n == 0) {
      cleanup.run();
      return out;
    }
    ExecutorService ex = Executors.newFixedThreadPool(Math.min(concurrency, n), daemons("kosa8"));
    List<Future<X>> futs = new ArrayList<>();
    try {
      for (int i = 0; i < n; i++) {
        int k = i;
        futs.add(ex.submit(() -> worker.apply(k)));
      }
      for (Future<X> f : futs) out.add(f.get());
      return out;
    } catch (InterruptedException e) {
      stop.set(true);
      ex.shutdownNow();
      Thread.currentThread().interrupt();
      throw new Kosa8Exception("interrupted; the map's sandboxes were deleted", e);
    } catch (ExecutionException e) {
      stop.set(true);
      ex.shutdownNow();
      Throwable c = e.getCause();
      if (c instanceof RuntimeException r) throw r;
      if (c instanceof Error er) throw er;
      throw new Kosa8Exception("a map worker failed: " + c, c);
    } finally {
      ex.shutdownNow();
      try {
        // Let interrupted workers give their children back before cleanup deletes what is left.
        ex.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      cleanup.run();
    }
  }

  static <T, R> List<ForkResult<T, R>> run(Kosa8Cloud cloud, CloudSandbox base, List<T> items, Fn<T, R> fn, CloudOptions.ForkMap o) {
    if (fn == null) throw new IllegalArgumentException("forkMap needs a function");
    List<T> list = new ArrayList<>(items == null ? List.of() : items);
    RetryPolicy policy = o.policy != null ? o.policy : RetryPolicy.attempts(o.retries + 1);
    ChildPool pool = new ChildPool(cloud, base, o.labels, o.runId, o.leaseS);
    AtomicBoolean stop = new AtomicBoolean();
    if (!list.isEmpty()) {
      try {
        pool.prefork(Math.min(list.size(), o.concurrency));
      } catch (RuntimeException | Error e) {
        pool.close();
        throw e;
      }
    }
    return runPool(list.size(), i -> {
      T item = list.get(i);
      long t0 = System.nanoTime();
      R value = null;
      Throwable error = null;
      String fc = "ok";
      String sid = null;
      int attempts = 0;
      for (int attempt = 1; attempt <= o.retries + 1; attempt++) {
        attempts = attempt;
        if (stop.get()) {
          error = cancelled();
          fc = "error";
          break;
        }
        CloudSandbox child = null;
        try {
          child = pool.take();
          sid = child.id();
          value = call(fn, child, item, o.timeout, pool);
          error = null;
          fc = "ok";
          break;
        } catch (Exception e) {
          value = null;
          error = e;
          FailureClass c = Kosa8Exception.classifyFailure(e);
          fc = c.failureClass();
          if (!c.retryable() || attempt > o.retries) break;
          pool.release(child);
          child = null;
          cloud.sleeper().accept(policy.delay(e, attempt));
        } finally {
          pool.release(child);
        }
      }
      return new ForkResult<>(item, value, error, fc, attempts, sid, (System.nanoTime() - t0) / 1_000_000);
    }, o.concurrency, stop, pool::close);
  }
}
