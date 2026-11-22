package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A sandbox's embeds ({@code box.embeds()}): signed, expiring iframe URLs for its terminal or desktop. The framed
 * page posts {source: "kosa8", type} messages (ready, exited, approval_requested, expired) to the listed origins.
 */
public final class CloudEmbeds {
  private final Kosa8Cloud cloud;
  private final String sandboxId;

  CloudEmbeds(Kosa8Cloud cloud, String sandboxId) {
    this.cloud = cloud;
    this.sandboxId = sandboxId;
  }

  private String p(String suffix) { return "/v1/sandboxes/" + Transport.enc(sandboxId) + suffix; }

  /** A view-only terminal embed framable by these origins, for an hour. */
  public Map<String, Object> create(List<String> origins) { return create(origins, null, null, null, null); }

  /**
   * An embed: origins (1 to 10, e.g. https://app.example.com) may frame it; mode view (default, watch only) or
   * interact; ui terminal (default) or desktop; ttlS 60 to 604800 (default 3600); command, for a terminal, the
   * program to show. Answers the embed with its url (the credential: give it only to pages you trust) and snippet.
   */
  public Map<String, Object> create(List<String> origins, String mode, String ui, Integer ttlS, List<String> command) {
    if (origins == null || origins.isEmpty() || origins.size() > 10) throw new IllegalArgumentException("an embed takes 1 to 10 origins");
    if (mode != null && !mode.equals("view") && !mode.equals("interact")) throw new IllegalArgumentException("mode must be view or interact");
    if (ui != null && !ui.equals("terminal") && !ui.equals("desktop")) throw new IllegalArgumentException("ui must be terminal or desktop");
    if (ttlS != null && (ttlS < 60 || ttlS > 604_800)) throw new IllegalArgumentException("ttlS must be from 60 to 604800");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("origins", List.copyOf(origins));
    if (mode != null) b.put("mode", mode);
    if (ui != null) b.put("ui", ui);
    if (ttlS != null) b.put("ttl_s", ttlS);
    if (command != null) b.put("command", List.copyOf(command));
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", p("/embed"), b)).get("embed"));
  }

  /** Its live embeds (no URLs). */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", p("/embeds"), null)).get("embeds")); }

  /** End an embed's URL before it expires. */
  public void revoke(String embedId) { cloud.request("DELETE", String.format("/v1/sandboxes/%s/embeds/%s", Transport.enc(sandboxId), Transport.enc(embedId)), null); }
}
