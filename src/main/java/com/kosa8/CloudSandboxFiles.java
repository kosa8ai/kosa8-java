package com.kosa8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files in a kosa8 Cloud sandbox ({@code box.files()}), at most 8 MiB each. Paths are absolute, such
 * as {@code /workspace/app.py}; only {@code /workspace} outlives an exec. A sleeping sandbox wakes first.
 *
 * <p>Needs the host's data plane; on hosts without it the API answers 501 ({@code not_available} /
 * {@code not_supported}), thrown as {@link Kosa8Exception.UnsupportedException}. Not every kosa8
 * Cloud host has it yet.
 */
public final class CloudSandboxFiles {
  private final CloudSandbox sb;

  CloudSandboxFiles(CloudSandbox sb) { this.sb = sb; }

  private String q(String suffix, String path, boolean recursive) {
    if (path == null || path.isEmpty()) throw new IllegalArgumentException("a path is required, e.g. /workspace/app.py");
    return sb.p(suffix) + "?path=" + Transport.enc(path) + (recursive ? "&recursive=1" : "");
  }

  /** The file's bytes. */
  public byte[] read(String path) { return sb.cloud().requestBytes("GET", q("/files", path, false)); }

  /** The file as UTF-8 text. */
  public String readText(String path) { return new String(read(path), StandardCharsets.UTF_8); }

  /** Create or replace a file with these bytes; returns its info. */
  public CloudFileInfo write(String path, byte[] content) {
    if (content == null) throw new IllegalArgumentException("content is required");
    return CloudFileInfo.of(Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().requestUpload("PUT", q("/files", path, false), content)).get("file")));
  }

  /** Create or replace a file with this text, as UTF-8; returns its info. */
  public CloudFileInfo write(String path, String content) {
    if (content == null) throw new IllegalArgumentException("content is required");
    return write(path, content.getBytes(StandardCharsets.UTF_8));
  }

  /** The entries of /workspace. */
  public List<CloudFileInfo> list() { return list("/workspace"); }

  /** A folder's entries. */
  public List<CloudFileInfo> list(String path) {
    List<CloudFileInfo> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(Kosa8Cloud.obj(sb.cloud().request("GET", q("/files/list", path, false), null)).get("files"))) {
      out.add(CloudFileInfo.of(m));
    }
    return out;
  }

  /** Make a folder and its parents; returns its info. */
  public CloudFileInfo mkdir(String path) {
    if (path == null || path.isEmpty()) throw new IllegalArgumentException("a path is required, e.g. /workspace/data");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("path", path);
    return CloudFileInfo.of(Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().request("POST", sb.p("/files/mkdir"), b)).get("file")));
  }

  /** Remove a file (or an empty folder). */
  public void delete(String path) { delete(path, false); }

  /** Remove a file, or with recursive a folder and everything in it. */
  public void delete(String path, boolean recursive) { sb.cloud().request("DELETE", q("/files", path, recursive), null); }
}
