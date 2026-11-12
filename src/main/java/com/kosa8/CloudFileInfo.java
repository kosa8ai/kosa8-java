package com.kosa8;

import java.util.Map;

/**
 * A file or folder in a kosa8 Cloud sandbox. type is "file" or "dir"; size is in bytes (null when
 * the host did not say); mode is like "-rw-r--r--"; modTime is the host's timestamp text.
 */
public record CloudFileInfo(String name, String path, String type, Long size, String mode, String modTime) {
  public boolean isDir() { return "dir".equals(type); }

  static CloudFileInfo of(Map<String, Object> m) {
    return new CloudFileInfo(CloudSandbox.s(m.get("name")), CloudSandbox.s(m.get("path")), CloudSandbox.s(m.get("type")),
        CloudSandbox.l(m.get("size")), CloudSandbox.s(m.get("mode")), CloudSandbox.s(m.get("mod_time")));
  }
}
