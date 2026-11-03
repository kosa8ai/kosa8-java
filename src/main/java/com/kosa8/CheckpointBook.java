package com.kosa8;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The stack logic of pkg/checkpoint over any store. */
final class CheckpointBook {
  /** pkg/checkpoint MaxPerSandbox. */
  static final int MAX_PER_SANDBOX = 10;
  private static final Pattern SEQ = Pattern.compile("^(\\d+)");
  final CheckpointStore store;

  CheckpointBook(CheckpointStore store) { this.store = store; }

  static void validate(String name) {
    if (name == null || name.isEmpty() || name.startsWith(".") || name.contains("/") || name.contains("\\"))
      throw new Kosa8Exception.CheckpointException("invalid sandbox name \"" + name + "\"");
  }

  static int seq(String id, String sandbox) {
    String pre = sandbox + "-ckpt-";
    if (!id.startsWith(pre)) return 0;
    Matcher m = SEQ.matcher(id.substring(pre.length()));
    return m.find() ? Integer.parseInt(m.group(1)) : 0;
  }

  record Next(String id, List<String> evict) {}

  Next nextId(String sandbox) {
    validate(sandbox);
    List<Checkpoint> list = store.load().getOrDefault(sandbox, List.of());
    int n = list.size() + 1;
    for (Checkpoint c : list) n = Math.max(n, seq(c.id(), sandbox) + 1);
    List<String> evict = new ArrayList<>();
    int over = list.size() + 1 - MAX_PER_SANDBOX;
    for (int i = 0; i < over; i++) evict.add(list.get(i).id());
    return new Next(sandbox + "-ckpt-" + n, evict);
  }

  Checkpoint record(String id, String sandbox, String label, List<String> evicted) {
    validate(sandbox);
    Checkpoint c = new Checkpoint(id, sandbox, label == null ? "" : label, false, Instant.now());
    store.update(d -> {
      List<Checkpoint> keep = new ArrayList<>();
      for (Checkpoint e : d.getOrDefault(sandbox, List.of())) if (!evicted.contains(e.id())) keep.add(e);
      keep.add(c);
      d.put(sandbox, keep);
      return null;
    });
    return c;
  }

  List<Checkpoint> list(String sandbox) {
    List<Checkpoint> l = new ArrayList<>(store.load().getOrDefault(sandbox, List.of()));
    l.sort(Comparator.comparing(Checkpoint::created)); // stable
    return l;
  }

  Checkpoint latest(String sandbox) {
    List<Checkpoint> l = list(sandbox);
    if (l.isEmpty())
      throw new Kosa8Exception.CheckpointException("no checkpoints for \"" + sandbox
          + "\" — take one with checkpoint() or `kosa8 checkpoint " + sandbox + "`");
    return l.get(l.size() - 1);
  }

  Checkpoint resolve(String sandbox, String ref) {
    List<Checkpoint> l = list(sandbox);
    for (int i = l.size() - 1; i >= 0; i--) {
      Checkpoint c = l.get(i);
      if (c.id().equals(ref) || c.label().equalsIgnoreCase(ref)) return c;
    }
    throw new Kosa8Exception.CheckpointException("no checkpoint \"" + ref + "\" for sandbox \"" + sandbox + "\"");
  }

  List<String> pop(String sandbox, String id) {
    return store.update(d -> {
      List<Checkpoint> l = d.getOrDefault(sandbox, List.of());
      for (int i = 0; i < l.size(); i++) {
        if (l.get(i).id().equals(id)) {
          List<String> dropped = new ArrayList<>();
          for (Checkpoint c : l.subList(i + 1, l.size())) dropped.add(c.id());
          d.put(sandbox, new ArrayList<>(l.subList(0, i + 1)));
          return dropped;
        }
      }
      throw new Kosa8Exception.CheckpointException("no checkpoint \"" + id + "\" for sandbox \"" + sandbox + "\"");
    });
  }

  List<String> forget(String sandbox) {
    return store.update(d -> {
      List<Checkpoint> l = d.remove(sandbox);
      List<String> ids = new ArrayList<>();
      if (l != null) for (Checkpoint c : l) ids.add(c.id());
      return ids;
    });
  }

}
