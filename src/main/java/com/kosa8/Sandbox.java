package com.kosa8;

import java.util.List;
import java.util.Map;

/** The client's sandbox methods bound to one sandbox id. */
public final class Sandbox {
  private final Kosa8Client c;
  private final String id;

  Sandbox(Kosa8Client c, String id) {
    this.c = c;
    this.id = id;
  }

  public String id() { return id; }
  public Map<String, Object> info() { return c.getSandbox(id); }
  public Map<String, Object> start() { return c.startSandbox(id); }
  public Map<String, Object> stop() { return c.stopSandbox(id); }
  public Map<String, Object> delete(Options.Delete o) { return c.deleteSandbox(id, o); }
  public Map<String, Object> delete() { return c.deleteSandbox(id); }
  public Map<String, Object> pull(String ref) { return c.pullIntoSandbox(id, ref); }
  public Results.Exec run(String image, List<String> args, Options.Run o) { return c.run(id, image, args, o); }
  public Results.Exec run(String image, List<String> args) { return c.run(id, image, args); }
  public String startContainer(String image, List<String> args) { return c.startContainer(id, image, args); }
  public Results.Exec exec(String container, List<String> args, Options.Run o) { return c.exec(id, container, args, o); }
  public List<Map<String, Object>> containers() { return c.sandboxContainers(id); }
  public List<String> images() { return c.sandboxImages(id); }
  public Map<String, Object> egress() { return c.sandboxEgress(id); }
  public Map<String, Object> snapshot(Options.Snapshot o) { return c.snapshot(id, o); }
  public Results.Fork fork(int n, Options.Fork o) { return c.fork(id, n, o); }
  public Results.Explore explore(List<String> variants, Options.Explore o) { return c.explore(id, variants, o); }
  public Results.CheckpointSaved checkpoint(String label) { return c.checkpoint(id, label); }
  public List<Checkpoint> checkpoints() { return c.listCheckpoints(id); }
  public Results.Rewind rewind(String to) { return c.rewind(id, to); }

  @Override
  public String toString() { return "Sandbox(" + id + ")"; }
}
