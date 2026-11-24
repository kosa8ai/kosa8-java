package com.kosa8;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** cloud.github(), repo on create, box.repo/push/pullRequest and the GitHub errors, against a stub control plane. */
public final class CloudGitHubTest extends ClientTest {
  static CloudTest.Answer ok(Object body) { return new CloudTest.Answer(200, body); }

  static Map<String, Object> m(Object... kv) { return CloudFeaturesTest.m(kv); }

  public void testGitHubStatusReposAndBranchesUseTheContractPaths() throws Exception {
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().equals("/v1/github")) return ok(m("configured", true, "connected", true, "can_manage", false, "install_url", null, "installations", List.of()));
      if (s.path().startsWith("/v1/github/repos?") || s.path().equals("/v1/github/repos")) {
        return ok(m("repos", List.of(m("full_name", "acme/api", "private", true, "default_branch", "main")), "next_page", 2));
      }
      return ok(m("default_branch", "main", "branches", List.of(m("name", "main", "sha", "abc", "protected", true))));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      eq(true, cloud.github().status().get("connected"), "connected");
      eq("acme/api", cloud.github().repos("api").get(0).get("full_name"), "repos");
      eq(2L, cloud.github().reposPage("x", 2).get("next_page"), "next page");
      eq("main", cloud.github().branches("acme/api").get("default_branch"), "branches");
      List<String> calls = new ArrayList<>();
      for (CloudTest.Seen s : stub.seen) calls.add(s.method() + " " + s.path());
      eq(List.of("GET /v1/github", "GET /v1/github/repos?q=api", "GET /v1/github/repos?q=x&page=2", "GET /v1/github/repos/acme/api/branches"),
          calls, "paths");
      raises(IllegalArgumentException.class, () -> cloud.github().branches("acme"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Create().repo("not a repo"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.Create().repo("acme/api@"));
      raises(IllegalArgumentException.class, () -> new CloudOptions.RepoSpec("acme/api").depth(0));
      eq(4, stub.seen.size(), "nothing sent for a bad repo");
    }
  }

  public void testRepoOnCreateAndPushAndPullRequest() throws Exception {
    Map<String, Object> repo = m("full_name", "acme/api", "ref", "main", "sha", "abc", "path", "/workspace/api", "status", "cloned");
    try (CloudTest.Stub stub = new CloudTest.Stub(s -> {
      if (s.path().endsWith("/repo")) return ok(m("repo", m("full_name", "acme/api", "status", "cloned", "changes", m("files_changed", 2))));
      if (s.path().endsWith("/github/push")) return ok(m("branch", "kosa8/abc", "sha", "def", "compare_url", "https://github.com/acme/api/compare/kosa8/abc", "files_changed", 2));
      if (s.path().endsWith("/github/pull-request")) return ok(m("number", 12, "url", "https://github.com/acme/api/pull/12", "branch", "fix", "sha", "def"));
      return ok(m("sandbox", m("id", "sbx_1", "state", "running", "repo", repo)));
    })) {
      Kosa8Cloud cloud = stub.cloud();
      CloudSandbox b = cloud.sandboxes().create(new CloudOptions.Create().repo("acme/api@main"));
      eq("cloned", b.repoInfo().get("status"), "repo block");
      eq(m("repo", "acme/api@main"), stub.seen.get(0).body(), "string repo sent as given");
      cloud.sandboxes().create(new CloudOptions.Create().repo(new CloudOptions.RepoSpec("acme/api").ref("v1.2").path("/workspace/x").depth(5)));
      eq(m("repo", m("name", "acme/api", "ref", "v1.2", "path", "/workspace/x", "depth", 5L)), stub.seen.get(1).body(), "object repo");
      eq(2L, ((Map<?, ?>) b.repo().get("changes")).get("files_changed"), "changes");
      eq("acme/api", b.repoInfo().get("full_name"), "repoInfo updated");
      eq("kosa8/abc", b.push().get("branch"), "push");
      eq("def", b.push(new CloudOptions.Push().branch("fix").message("m")).get("sha"), "push with options");
      eq(12L, b.pullRequest(new CloudOptions.PullRequest().title("Fix").base("main").draft(false)).get("number"), "pr");
      List<String> calls = new ArrayList<>();
      for (CloudTest.Seen s : stub.seen.subList(2, stub.seen.size())) calls.add(s.method() + " " + s.path());
      eq(List.of("GET /v1/sandboxes/sbx_1/repo", "POST /v1/sandboxes/sbx_1/github/push", "POST /v1/sandboxes/sbx_1/github/push",
          "POST /v1/sandboxes/sbx_1/github/pull-request"), calls, "sandbox paths");
      eq(m(), stub.seen.get(3).body(), "push defaults: empty body");
      eq(m("branch", "fix", "message", "m"), stub.seen.get(4).body(), "push body");
      eq(m("title", "Fix", "base", "main", "draft", false), stub.seen.get(5).body(), "pr body");
    }
  }

  public void testGitHubErrorsAreTyped() {
    Kosa8Exception nc = Kosa8Cloud.cloudError(409, m("error", "github_not_connected", "message", "Connect GitHub first.",
        "connect_url", "https://app.kosa8.com/app#settings/github"), "POST", "/v1/sandboxes");
    check(nc instanceof Kosa8Exception.GitHubNotConnectedException, "not connected: " + nc.getClass());
    check(nc instanceof Kosa8Exception.ConflictException, "still a conflict");
    eq("https://app.kosa8.com/app#settings/github", ((Kosa8Exception.ApiException) nc).connectUrl(), "connect url");
    eq("github_not_connected", nc.code(), "code");
    Kosa8Exception.ApiException pe = (Kosa8Exception.ApiException) Kosa8Cloud.cloudError(409, m("error", "github_permissions",
        "message", "Approve.", "missing", List.of("pull_requests:write"), "settings_url", "https://github.com/settings/installations/7"), "POST", "/x");
    check(pe instanceof Kosa8Exception.GitHubPermissionsException, "permissions");
    eq(List.of("pull_requests:write"), pe.missingPermissions(), "missing");
    eq("https://github.com/settings/installations/7", pe.settingsUrl(), "settings url");
    check(Kosa8Cloud.cloudError(409, m("error", "no_changes", "message", "Nothing changed."), "POST", "/x") instanceof Kosa8Exception.NoChangesException, "no changes");
    Kosa8Exception nf = Kosa8Cloud.cloudError(404, m("error", "repo_not_found", "message", "No such repo."), "POST", "/x");
    check(nf instanceof Kosa8Exception.RepoNotFoundException && nf instanceof Kosa8Exception.NotFoundException, "repo not found");
    Kosa8Exception un = Kosa8Cloud.cloudError(502, m("error", "github_unavailable", "message", "GitHub is busy.", "retry_after_s", 30), "POST", "/x");
    check(un instanceof Kosa8Exception.GitHubUnavailableException, "unavailable");
    eq(30.0, un.retryAfterSeconds(), "retry after");
    check(Kosa8Cloud.cloudError(409, m("error", "conflict", "message", "no"), "POST", "/x").getClass() == Kosa8Exception.ConflictException.class, "plain 409 unchanged");
  }
}
