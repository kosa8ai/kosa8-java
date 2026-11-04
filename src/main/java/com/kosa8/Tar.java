package com.kosa8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Packs a build context the way {@code kosa8 build} does: a ustar archive of the folder,
 * honouring {@code .dockerignore} prefixes. Names that do not fit ustar's 100-byte name
 * (plus 155-byte prefix) are carried in a PAX extended header, which the daemon's reader
 * (Go's archive/tar) understands. Symlinks are stored as links, not followed; sockets
 * and other special files are left out.
 */
final class Tar {
  private Tar() {}

  /** The largest file ustar's 11 octal digits can describe. */
  static final long MAX_SIZE = 077777777777L;

  /** Writes {@code dir} as a tar to {@code out}. Throws if {@code dockerfile} is not in it. */
  static void context(Path dir, String dockerfile, OutputStream out) throws IOException {
    if (!Files.isDirectory(dir)) throw new Kosa8Exception("build context " + dir + " is not a folder");
    if (!Files.exists(dir.resolve(dockerfile))) throw new Kosa8Exception("no " + dockerfile + " in " + dir.toAbsolutePath());
    List<String> ignore = dockerignore(dir);
    Tar.Writer w = new Tar.Writer(out);
    walk(dir, dir, ignore, w);
    w.finish();
  }

  /** The .dockerignore patterns, normalised: path prefixes relative to the context. */
  static List<String> dockerignore(Path dir) throws IOException {
    Path f = dir.resolve(".dockerignore");
    List<String> out = new ArrayList<>();
    if (!Files.isRegularFile(f)) return out;
    for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
      String s = l.strip();
      if (s.isEmpty() || s.startsWith("#")) continue;
      while (s.startsWith("/")) s = s.substring(1);
      String n = Path.of(s).normalize().toString().replace('\\', '/');
      if (!n.isEmpty()) out.add(n);
    }
    return out;
  }

  static boolean ignored(String rel, List<String> ignore) {
    for (String p : ignore) if (rel.equals(p) || rel.startsWith(p + "/")) return true;
    return false;
  }

  private static void walk(Path root, Path dir, List<String> ignore, Writer w) throws IOException {
    Path[] kids;
    try (Stream<Path> s = Files.list(dir)) {
      kids = s.toArray(Path[]::new);
    }
    Arrays.sort(kids);
    for (Path p : kids) {
      String rel = root.relativize(p).toString().replace('\\', '/');
      if (ignored(rel, ignore)) continue;
      BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      long mtime = a.lastModifiedTime().toMillis() / 1000;
      if (a.isSymbolicLink()) {
        w.entry(rel, '2', 0777, 0, mtime, Files.readSymbolicLink(p).toString().replace('\\', '/'), null);
      } else if (a.isDirectory()) {
        w.entry(rel + "/", '5', mode(p, 0755), 0, mtime, "", null);
        walk(root, p, ignore, w);
      } else if (a.isRegularFile()) {
        if (a.size() > MAX_SIZE)
          throw new Kosa8Exception(rel + " is " + a.size() + " bytes, more than a tar entry can hold; leave it out with .dockerignore");
        try (InputStream in = Files.newInputStream(p)) {
          w.entry(rel, '0', mode(p, 0644), a.size(), mtime, "", in);
        }
      }
      // sockets, fifos and devices have no place in a build context
    }
  }

  private static int mode(Path p, int dflt) {
    try {
      Set<PosixFilePermission> perms = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
      int m = 0;
      for (PosixFilePermission x : perms) m |= 1 << (8 - x.ordinal());
      return m;
    } catch (UnsupportedOperationException | IOException e) {
      return dflt;
    }
  }

  /** A minimal ustar writer with PAX headers for long names and link targets. */
  static final class Writer {
    private final OutputStream out;
    private int pax;

    Writer(OutputStream out) { this.out = out; }

    void entry(String name, char type, int mode, long size, long mtime, String link, InputStream data) throws IOException {
      byte[] nb = name.getBytes(StandardCharsets.UTF_8);
      byte[] lb = link.getBytes(StandardCharsets.UTF_8);
      String prefix = "";
      String shortName = name;
      StringBuilder records = new StringBuilder();
      if (nb.length > 100) {
        int cut = split(name);
        if (cut > 0) {
          prefix = name.substring(0, cut);
          shortName = name.substring(cut + 1);
        } else {
          records.append(record("path", name));
          shortName = truncate(name, 100);
        }
      }
      if (lb.length > 100) {
        records.append(record("linkpath", link));
        link = truncate(link, 100);
      }
      if (records.length() > 0) {
        byte[] body = records.toString().getBytes(StandardCharsets.UTF_8);
        header(truncate("PaxHeaders/" + (pax++), 100), "", 'x', 0644, body.length, mtime, "");
        out.write(body);
        pad(body.length);
      }
      header(shortName, prefix, type, mode, size, mtime, link);
      if (data != null) {
        byte[] buf = new byte[64 * 1024];
        long left = size;
        int n;
        while (left > 0 && (n = data.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
          out.write(buf, 0, n);
          left -= n;
        }
        if (left > 0) throw new IOException(name + " shrank while it was being packed");
        pad(size);
      }
    }

    void finish() throws IOException {
      out.write(new byte[1024]);
      out.flush();
    }

    /** Where to split a long name into ustar prefix and name, or -1. */
    private static int split(String name) {
      for (int i = name.length() - 2; i > 0; i--) { // a folder's trailing / stays with its name
        if (name.charAt(i) != '/') continue;
        int pre = name.substring(0, i).getBytes(StandardCharsets.UTF_8).length;
        int rest = name.substring(i + 1).getBytes(StandardCharsets.UTF_8).length;
        if (pre > 155) continue;
        if (rest == 0 || rest > 100) return -1;
        return i;
      }
      return -1;
    }

    private static String truncate(String s, int max) {
      byte[] b = s.getBytes(StandardCharsets.UTF_8);
      if (b.length <= max) return s;
      // Cut at a character boundary so the field stays valid UTF-8.
      int end = max;
      while (end > 0 && (b[end] & 0xC0) == 0x80) end--;
      return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    /** One PAX record: "LEN key=value\n", where LEN counts itself. */
    static String record(String key, String value) {
      int body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8).length;
      int len = body + String.valueOf(body).length();
      if (String.valueOf(len).length() != String.valueOf(body).length()) len = body + String.valueOf(len).length();
      return len + " " + key + "=" + value + "\n";
    }

    private void header(String name, String prefix, char type, int mode, long size, long mtime, String link) throws IOException {
      byte[] h = new byte[512];
      put(h, 0, 100, name.getBytes(StandardCharsets.UTF_8));
      octal(h, 100, 8, mode);
      octal(h, 108, 8, 0);
      octal(h, 116, 8, 0);
      octal(h, 124, 12, size);
      octal(h, 136, 12, Math.max(0, mtime));
      Arrays.fill(h, 148, 156, (byte) ' ');
      h[156] = (byte) type;
      put(h, 157, 100, link.getBytes(StandardCharsets.UTF_8));
      put(h, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));
      put(h, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));
      put(h, 345, 155, prefix.getBytes(StandardCharsets.UTF_8));
      long sum = 0;
      for (byte b : h) sum += b & 0xff;
      byte[] cs = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
      System.arraycopy(cs, 0, h, 148, 8);
      out.write(h);
    }

    private static void put(byte[] h, int off, int len, byte[] v) {
      if (v.length > len) throw new IllegalStateException("tar field overflow");
      System.arraycopy(v, 0, h, off, v.length);
    }

    private static void octal(byte[] h, int off, int len, long v) {
      String s = Long.toOctalString(v);
      if (s.length() > len - 1) throw new Kosa8Exception("value " + v + " does not fit a tar header");
      String padded = "0".repeat(len - 1 - s.length()) + s;
      put(h, off, len, (padded + "\0").getBytes(StandardCharsets.US_ASCII));
    }

    private void pad(long n) throws IOException {
      int r = (int) (n % 512);
      if (r != 0) out.write(new byte[512 - r]);
    }
  }
}
