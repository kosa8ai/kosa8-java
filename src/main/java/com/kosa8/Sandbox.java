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
  public void execFrames(String container, List<String> args, java.util.function.Consumer<Frame> fn, Options.Approval o) {
    c.execFrames(id, container, args, fn, o);
  }
  public String startInteractive(String image, List<String> args, Options.Interactive o) {
    return c.startInteractive(id, image, args, o);
  }
  public Map<String, Object> writeStdin(String container, java.io.InputStream data, Options.Approval o) {
    return c.writeStdin(id, container, data, o);
  }
  public Map<String, Object> writeStdin(String container, byte[] data, Options.Approval o) {
    return c.writeStdin(id, container, data, o);
  }
  public Results.Exec attach(String container, Options.Run o) { return c.attach(id, container, o); }
  public List<Map<String, Object>> containers() { return c.sandboxContainers(id); }
  public List<String> images() { return c.sandboxImages(id); }
  public Map<String, Object> egress() { return c.sandboxEgress(id); }
  public Map<String, Object> snapshot(Options.Snapshot o) { return c.snapshot(id, o); }
  public Results.Fork fork(int n, Options.Fork o) { return c.fork(id, n, o); }
  public Results.Explore explore(List<String> variants, Options.Explore o) { return c.explore(id, variants, o); }
  public Results.CheckpointSaved checkpoint(String label) { return c.checkpoint(id, label); }
  public List<Checkpoint> checkpoints() { return c.listCheckpoints(id); }
  public Results.Rewind rewind(String to) { return c.rewind(id, to); }

  // --- toolbox
  public Results.CodeResult runCode(String code, Options.Code o) { return c.runCode(id, code, o); }
  public Results.CodeResult runCode(String code) { return c.runCode(id, code); }
  public List<Results.FileEntry> listFiles(String path) { return c.listFiles(id, path); }
  public List<Results.FileEntry> listFiles() { return c.listFiles(id); }
  public Results.FileEntry statFile(String path) { return c.statFile(id, path); }
  public byte[] readFile(String path) { return c.readFile(id, path); }
  public String readText(String path) { return c.readText(id, path); }
  public Map<String, Object> writeFile(String path, byte[] data, Options.WriteFile o) { return c.writeFile(id, path, data, o); }
  public Map<String, Object> writeFile(String path, byte[] data) { return c.writeFile(id, path, data); }
  public Map<String, Object> writeFile(String path, String text, Options.WriteFile o) { return c.writeFile(id, path, text, o); }
  public Map<String, Object> writeFile(String path, String text) { return c.writeFile(id, path, text); }
  public Map<String, Object> writeFile(String path, java.io.InputStream data, Options.WriteFile o) { return c.writeFile(id, path, data, o); }
  public Map<String, Object> deleteFile(String path, Options.DeleteFile o) { return c.deleteFile(id, path, o); }
  public Map<String, Object> deleteFile(String path) { return c.deleteFile(id, path); }
  public Map<String, Object> makeDir(String path) { return c.makeDir(id, path); }
  public Map<String, Object> moveFile(String from, String to) { return c.moveFile(id, from, to); }
  public Results.GitResult git(String op, Options.Git o) { return c.git(id, op, o); }
  public Results.GitResult gitClone(String url, String path, Options.Git o) { return c.gitClone(id, url, path, o); }
  public Results.GitResult gitClone(String url, String path) { return c.gitClone(id, url, path); }
  public Results.GitResult gitStatus(String path) { return c.gitStatus(id, path); }
  public Results.GitResult gitCommit(String path, String message, Options.Git o) { return c.gitCommit(id, path, message, o); }
  public Results.GitResult gitCommit(String path, String message) { return c.gitCommit(id, path, message); }
  public Results.Session createSession(String name, Options.Session o) { return c.createSession(id, name, o); }
  public Results.Session createSession(String name) { return c.createSession(id, name); }
  public List<Results.Session> sessions() { return c.listSessions(id); }
  public Results.ShellResult sessionExec(String session, String command, Options.Shell o) { return c.sessionExec(id, session, command, o); }
  public Results.ShellResult sessionExec(String session, String command) { return c.sessionExec(id, session, command); }
  public Results.ShellResult shell(String command, Options.Shell o) { return c.shell(id, command, o); }
  public Results.ShellResult shell(String command) { return c.shell(id, command); }
  public Map<String, Object> deleteSession(String session) { return c.deleteSession(id, session); }
  public String startPty(Options.Pty o) { return c.startPty(id, o); }
  public Map<String, Object> resizePty(String container, int cols, int rows) { return c.resizePty(id, container, cols, rows); }
  public Results.Preview createPreview(int port, Options.Preview o) { return c.createPreview(id, port, o); }
  public Results.Preview createPreview(int port) { return c.createPreview(id, port); }
  public Map<String, Object> setLifecycle(Options.Lifecycle o) { return c.setLifecycle(id, o); }

  @Override
  public String toString() { return "Sandbox(" + id + ")"; }
}
