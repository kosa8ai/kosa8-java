package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code cloud.apiKeys()}: the org's API keys (k8c_live_...). A secret is shown only in the answer that made it. */
public final class CloudApiKeys {
  private final Kosa8Cloud cloud;

  CloudApiKeys(Kosa8Cloud cloud) { this.cloud = cloud; }

  /** The keys, never secrets. */
  public List<Map<String, Object>> list() { return Kosa8Cloud.objs(Kosa8Cloud.obj(cloud.request("GET", "/v1/api-keys", null)).get("api_keys")); }

  /**
   * A new key: name, scopes (null: the server's default) and expiresInS (null: never). The answer's secret is
   * shown only now.
   */
  public Map<String, Object> create(String name, List<String> scopes, Integer expiresInS) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (name != null) b.put("name", name);
    if (scopes != null) b.put("scopes", List.copyOf(scopes));
    if (expiresInS != null) b.put("expires_in_s", expiresInS);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(cloud.request("POST", "/v1/api-keys", b)).get("api_key"));
  }

  /** Revoke a key now. */
  public Map<String, Object> revoke(String id) { return Kosa8Cloud.obj(cloud.request("DELETE", "/v1/api-keys/" + Transport.enc(id), null)); }

  /**
   * A new key with the same name and scopes; the old one keeps working graceS more seconds (null: the
   * default). Answers {api_key (with its secret), old_key, note}.
   */
  public Map<String, Object> rotate(String id, Integer graceS) {
    Map<String, Object> b = new LinkedHashMap<>();
    if (graceS != null) b.put("grace_s", graceS);
    return Kosa8Cloud.obj(cloud.request("POST", "/v1/api-keys/" + Transport.enc(id) + "/rotate", b));
  }
}
