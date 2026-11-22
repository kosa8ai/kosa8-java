package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A preview's custom domains ({@code box.previews().domains(port)} or {@code cloud.previewDomains(previewId)}),
 * at most 5 a preview and 50 an org. The domain serves the preview as it is, private or public, and follows it
 * across a rotate. 501 not_available where custom hostnames are not configured.
 */
public final class CloudPreviewDomains {
  private final Kosa8Cloud cloud;
  private final String previewId;
  private final String sandboxId;
  private final int port;

  CloudPreviewDomains(Kosa8Cloud cloud, String previewId, String sandboxId, int port) {
    this.cloud = cloud;
    this.previewId = previewId;
    this.sandboxId = sandboxId;
    this.port = port;
  }

  private String base() {
    return previewId != null ? String.format("/v1/previews/%s/domains", Transport.enc(previewId))
        : String.format("/v1/sandboxes/%s/previews/%d/domains", Transport.enc(sandboxId), port);
  }

  private String one(String hostname) {
    if (hostname == null || hostname.isEmpty()) throw new IllegalArgumentException("a hostname is required");
    String h = Transport.enc(hostname);
    return previewId != null ? String.format("/v1/previews/%s/domains/%s", Transport.enc(previewId), h)
        : String.format("/v1/sandboxes/%s/previews/%d/domains/%s", Transport.enc(sandboxId), port, h);
  }

  /** {domains, configured, cname_target}. */
  public Map<String, Object> info() { return Kosa8Cloud.obj(cloud.request("GET", base(), null)); }

  /** Its domains: hostname, url, status, ssl_status, records (the DNS records to make), error. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(info().get("domains")); }

  /** Serve the preview at hostname (under your own domain); make the records it answers at your DNS provider. */
  public Map<String, Object> add(String hostname) { return add(hostname, null); }

  /** As {@link #add(String)}; sslMethod http or txt (null: the default). */
  public Map<String, Object> add(String hostname, String sslMethod) {
    if (hostname == null || hostname.isEmpty()) throw new IllegalArgumentException("a hostname is required, e.g. app.example.com");
    if (sslMethod != null && !sslMethod.equals("http") && !sslMethod.equals("txt")) throw new IllegalArgumentException("sslMethod must be http or txt");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("hostname", hostname);
    if (sslMethod != null) b.put("ssl_method", sslMethod);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", base(), b)).get("domain"));
  }

  /** A domain's status, asked of the certificate provider again. */
  public Map<String, Object> get(String hostname) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("GET", one(hostname), null)).get("domain"));
  }

  /** Stop serving the preview at hostname. */
  public void remove(String hostname) { cloud.request("DELETE", one(hostname), null); }
}
