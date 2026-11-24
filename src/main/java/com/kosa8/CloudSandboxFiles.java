package com.kosa8;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Files in a kosa8 Cloud sandbox ({@code box.files()}). Paths are absolute, such as {@code /workspace/app.py};
 * only {@code /workspace} outlives an exec. A sleeping sandbox wakes first. Through the API one request body is at
 * most 100 MB: {@link #uploadFile} sends a bigger file in parts, and {@link #downloadTo} streams one to disk. A host
 * without streamed files caps a file at 8 MiB.
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

  /** A file of any size (no 8 MiB cap), streamed by the host. */
  public byte[] download(String path) { return sb.cloud().requestBytes("GET", q("/files/raw", path, false)); }

  /**
   * Write a file streamed to the host (no 8 MiB cap); returns its info. One request body is at most 100 MB
   * through the API: {@link #uploadFile} sends a bigger one in parts.
   */
  public CloudFileInfo upload(String path, byte[] content) {
    if (content == null) throw new IllegalArgumentException("content is required");
    return CloudFileInfo.of(Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().requestUpload("PUT", q("/files/raw", path, false), content)).get("file")));
  }

  /**
   * Bytes start to end of a file, both inclusive (end null: to the end of the file), with an HTTP Range: a
   * part of a big file or a log's tail without downloading it all. 416 past the end of the file.
   */
  public byte[] readRange(String path, long start, Long end) {
    if (start < 0 || (end != null && end < start)) throw new IllegalArgumentException("need 0 <= start <= end, not " + start + "-" + end);
    return sb.cloud().requestBytes("GET", q("/files", path, false), Map.of("Range", "bytes=" + start + "-" + (end == null ? "" : end)));
  }

  /**
   * Write data at byte offset of the file, keeping the rest of it and growing it as needed: the parts of a big
   * upload, in any order. Safe to retry (the same bytes land in the same place).
   */
  public CloudFileInfo writeAt(String path, byte[] data, long offset) {
    if (data == null) throw new IllegalArgumentException("data is required");
    if (offset < 0) throw new IllegalArgumentException("offset must be 0 or more");
    return CloudFileInfo.of(Kosa8Cloud.obj(Kosa8Cloud.obj(
        sb.cloud().requestUpload("PUT", q("/files", path, false) + "&offset=" + offset, data)).get("file")));
  }

  /**
   * Add data at the end of the file (made if missing). Never retried after it may have reached the host, since
   * a second send would add it twice; use {@link #writeAt} where a retry must be safe.
   */
  public CloudFileInfo append(String path, byte[] data) {
    if (data == null) throw new IllegalArgumentException("data is required");
    return CloudFileInfo.of(Kosa8Cloud.obj(Kosa8Cloud.obj(
        sb.cloud().requestUpload("PUT", q("/files", path, false) + "&append=1", data)).get("file")));
  }

  /** The size of one part of {@link #uploadFile}: under the API's 100 MB request limit. */
  public static final int PART_BYTES = 64 * 1024 * 1024;

  /**
   * Upload a local file of any size: the first part replaces the file, the rest are written at their offsets
   * (each retried on its own), so a file over the API's 100 MB request limit works. Holds one part in memory.
   */
  public CloudFileInfo uploadFile(String path, java.nio.file.Path local) {
    return uploadFile(path, local, PART_BYTES);
  }

  CloudFileInfo uploadFile(String path, java.nio.file.Path local, int partBytes) {
    if (local == null) throw new IllegalArgumentException("a local file is required");
    try (java.io.InputStream in = java.nio.file.Files.newInputStream(local)) {
      CloudFileInfo info = null;
      long off = 0;
      while (true) {
        byte[] part = in.readNBytes(partBytes);
        if (off == 0) info = write(path, part);
        else if (part.length > 0) info = writeAt(path, part, off);
        if (part.length < partBytes) return info;
        off += part.length;
      }
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException("could not read " + local, e);
    }
  }

  /** Download a file of any size straight to local (replaced), streamed, never held whole in memory. */
  public void downloadTo(String path, java.nio.file.Path local) {
    if (local == null) throw new IllegalArgumentException("a local file is required");
    sb.cloud().requestToFile(q("/files", path, false), local);
  }

  /**
   * A presigned URL for one download (GET) or upload (PUT) of path, straight to the host: url, method, path,
   * expires_at. ttlS null takes the default. Keep the URL secret.
   */
  public Map<String, Object> link(String path, String method, Integer ttlS) {
    if (path == null || path.isEmpty()) throw new IllegalArgumentException("a path is required, e.g. /workspace/app.py");
    String m = method == null ? "GET" : method.toUpperCase(java.util.Locale.ROOT);
    if (!m.equals("GET") && !m.equals("PUT")) throw new IllegalArgumentException("method must be GET or PUT, not " + method);
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("path", path);
    b.put("method", m);
    if (ttlS != null) b.put("ttl_s", ttlS);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().request("POST", sb.p("/files/link"), b)).get("link"));
  }

  /** Remove a file (or an empty folder). */
  public void delete(String path) { delete(path, false); }

  /** Remove a file, or with recursive a folder and everything in it. */
  public void delete(String path, boolean recursive) { sb.cloud().request("DELETE", q("/files", path, recursive), null); }
}
