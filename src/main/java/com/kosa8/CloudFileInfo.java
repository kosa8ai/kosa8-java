package com.kosa8;

import java.util.Map;

/**
 * A file or folder in a kosa8 Cloud sandbox. type is "file" or "dir"; size is in bytes (null when
 * the host did not say); mode is the Unix permission bits as the host sends them (420 is 0644), null when it did not say; modTime is the host's timestamp text.
 */
public record CloudFileInfo(String name, String path, String type, Long size, Integer mode, String modTime) {
  public boolean isDir() { return "dir".equals(type); }

  static CloudFileInfo of(Map<String, Object> m) {
    return new CloudFileInfo(CloudSandbox.s(m.get("name")), CloudSandbox.s(m.get("path")), CloudSandbox.s(m.get("type")),
        CloudSandbox.l(m.get("size")), m.get("mode") instanceof Number n ? n.intValue() : null, CloudSandbox.s(m.get("mod_time")));
  }
}
