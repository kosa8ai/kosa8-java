package com.kosa8;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A sandbox's schedules ({@code box.schedules()}): commands run on a cron schedule (UTC). */
public final class CloudSchedules {
  private final CloudSandbox sb;

  CloudSchedules(CloudSandbox sb) { this.sb = sb; }

  /** Run cmd on cron (five fields, UTC, e.g. "0 * * * *"). */
  public Map<String, Object> create(String cron, List<String> cmd) { return create(cron, cmd, null, null, null); }

  /** Run cmd on cron with env; name and wake (wake a sleeping sandbox for it) may be null. Returns the schedule. */
  public Map<String, Object> create(String cron, List<String> cmd, Map<String, String> env, String name, Boolean wake) {
    if (cron == null || cron.isEmpty() || cmd == null || cmd.isEmpty()) throw new IllegalArgumentException("a schedule needs cron and cmd");
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("cron", cron);
    b.put("cmd", List.copyOf(cmd));
    if (env != null && !env.isEmpty()) b.put("env", CloudOptions.envList(env));
    if (name != null) b.put("name", name);
    if (wake != null) b.put("wake", wake);
    return Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().request("POST", sb.p("/schedules"), b)).get("schedule"));
  }

  /** Its schedules, each with its last_run. */
  public List<Map<String, Object>> list() {
    return Kosa8Cloud.objs(Kosa8Cloud.obj(sb.cloud().request("GET", sb.p("/schedules"), null)).get("schedules"));
  }

  public void delete(String id) { sb.cloud().request("DELETE", sb.p("/schedules/" + Transport.enc(id)), null); }

  /** Run it now; returns the run (status, exit, output, ms). */
  public Map<String, Object> run(String id) {
    return Kosa8Cloud.obj(Kosa8Cloud.obj(sb.cloud().request("POST", sb.p("/schedules/" + Transport.enc(id) + "/run"), new LinkedHashMap<>())).get("run"));
  }
}
