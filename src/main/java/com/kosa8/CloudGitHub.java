package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code cloud.github()}: the org's connected GitHub. An admin connects the kosa8 GitHub App once (app.kosa8.com
 * Settings, or {@code kosa8 github connect}); then a sandbox starts from a repo
 * ({@link CloudOptions.Create#repo(String)}) and sends changes back ({@link CloudSandbox#push},
 * {@link CloudSandbox#pullRequest}). kosa8 mints a GitHub token for one repo and one operation that expires
 * within an hour; it never enters the sandbox, is never returned, and every use is audited.
 */
public final class CloudGitHub {
  private final Kosa8Cloud cloud;

  CloudGitHub(Kosa8Cloud cloud) { this.cloud = cloud; }

  /**
   * {configured, connected, can_manage, install_url (admins only, else null), installations: [{installation_id,
   * account_login, account_type, repository_selection, repo_count, manage_url, missing_permissions}]}.
   */
  public Map<String, Object> status() { return Kosa8Cloud.obj(cloud.request("GET", "/v1/github", null)); }

  /** Repos the org's installations can see, most recently pushed first; query (null for all) filters by substring. */
  public List<Map<String, Object>> repos(String query) { return Kosa8Cloud.objs(reposPage(query, null).get("repos")); }

  /** All visible repos (the first page). */
  public List<Map<String, Object>> repos() { return repos(null); }

  /**
   * One page: {repos: [{full_name, private, default_branch, description, pushed_at, html_url, installation_id,
   * language}], next_page (null on the last page)}.
   */
  public Map<String, Object> reposPage(String query, Integer page) {
    Map<String, Object> q = new LinkedHashMap<>();
    if (query != null && !query.isEmpty()) q.put("q", query);
    if (page != null) q.put("page", page);
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/github/repos" + CloudAudit.query(q), null));
  }

  /** A repo's branches (owner/name): {default_branch, branches: [{name, sha, protected}]} (the first 100). */
  public Map<String, Object> branches(String repo) {
    String[] on = CloudOptions.checkRepoName(repo).split("/", 2);
    return Kosa8Cloud.obj(cloud.request("GET", "/v1/github/repos/" + Transport.enc(on[0]) + "/" + Transport.enc(on[1]) + "/branches", null));
  }
}
