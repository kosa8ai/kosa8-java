package com.kosa8;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A sandbox's previews ({@code box.previews()}): its ports at https URLs, private (signed links) unless public. */
public final class CloudPreviews {
  private final CloudSandbox sb;

  CloudPreviews(CloudSandbox sb) { this.sb = sb; }

  private static int check(int port) {
    if (port < 1 || port > 65535) throw new IllegalArgumentException("a port is 1 to 65535, not " + port);
    return port;
  }

  private static CloudPreview one(Object resp) { return CloudPreview.of(Kosa8Cloud.obj(Kosa8Cloud.obj(resp).get("preview"))); }

  /** Open a port as a private preview with the server's default link lifetime. */
  public CloudPreview create(int port) { return create(port, false, null, null); }

  /**
   * Open a port: isPublic needs no signed link; expiresInS is a private link's lifetime and wake whether a
   * request wakes a sleeping sandbox (null: the server's defaults).
   */
  public CloudPreview create(int port, boolean isPublic, Integer expiresInS, Boolean wake) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("port", check(port));
    if (isPublic) b.put("public", true);
    if (expiresInS != null) b.put("expires_in_s", expiresInS);
    if (wake != null) b.put("wake", wake);
    return one(sb.cloud().request("POST", sb.p("/previews"), b));
  }

  /** Its previews, without tokens. */
  public List<CloudPreview> list() {
    List<CloudPreview> out = new ArrayList<>();
    for (Map<String, Object> m : Kosa8Cloud.objs(Kosa8Cloud.obj(sb.cloud().request("GET", sb.p("/previews"), null)).get("previews"))) {
      out.add(CloudPreview.of(m));
    }
    return out;
  }

  /** A new signed link for a private preview (expiresInS null: the default). */
  public CloudPreview link(int port, Integer expiresInS) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (expiresInS != null) b.put("expires_in_s", expiresInS);
    return one(sb.cloud().request("POST", sb.p("/previews/" + check(port) + "/link"), b));
  }

  /** A new host name, so every old link stops working. */
  public CloudPreview rotate(int port) {
    return one(sb.cloud().request("POST", sb.p("/previews/" + check(port) + "/rotate"), new LinkedHashMap<>()));
  }

  /** Close the port's preview. */
  public void delete(int port) {
    sb.cloud().request("DELETE", String.format("/v1/sandboxes/%s/previews/%d", Transport.enc(sb.id()), check(port)), null);
  }

  /** The port's custom domains: serve its preview at your own hostname. */
  public CloudPreviewDomains domains(int port) { return new CloudPreviewDomains(sb.cloud(), null, sb.id(), check(port)); }
}
