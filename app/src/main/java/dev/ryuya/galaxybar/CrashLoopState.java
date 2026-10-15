package dev.ryuya.galaxybar;

import static android.app.ApplicationExitInfo.*;

import java.util.*;

/** Persistent policy only: counts exits of processes in which this module armed its hooks. */
final class CrashLoopState {
  static final int LIMIT = 3;
  static final long WINDOW_MS = 120_000, UNKNOWN_WINDOW_MS = 30_000;

  record Exit(int pid, long time, int reason, int signal) {}

  final LinkedHashMap<Integer, Long> launches = new LinkedHashMap<>();
  final TreeSet<Long> crashes = new TreeSet<>();
  boolean blocked;
  int boot = -1;
  long lastObserved;
  String resetToken = "", cause = "";

  static boolean abnormal(int reason, int signal) {
    return reason == REASON_CRASH
        || reason == REASON_CRASH_NATIVE
        || reason == REASON_ANR
        || reason == REASON_EXCESSIVE_RESOURCE_USAGE
        || reason == REASON_INITIALIZATION_FAILURE
        || (reason == REASON_SIGNALED && (signal == 6 || signal == 11));
  }

  void observe(long now, int currentBoot, String requestedReset, List<Exit> exits) {
    if (!requestedReset.isEmpty() && !requestedReset.equals(resetToken)) {
      blocked = false;
      cause = "";
      launches.clear();
      crashes.clear();
      resetToken = requestedReset;
    }
    if (boot != currentBoot || now < lastObserved) {
      launches.clear();
      crashes.clear();
      boot = currentBoot;
    }
    lastObserved = now;
    crashes.removeIf(t -> t > now || now - t > WINDOW_MS);
    for (Exit exit : exits) {
      Long started = launches.get(exit.pid);
      if (started == null || exit.time < started || exit.time > now) continue;
      if (exit.reason == REASON_UNKNOWN) continue;
      launches.remove(exit.pid);
      if (abnormal(exit.reason, exit.signal) && now - exit.time <= WINDOW_MS)
        crashes.add(exit.time);
    }
    if (!blocked && crashes.size() >= LIMIT) {
      blocked = true;
      cause = "crash_loop";
    }
    long unresolved =
        launches.values().stream().filter(t -> t <= now && now - t <= UNKNOWN_WINDOW_MS).count();
    if (!blocked && unresolved >= LIMIT) {
      blocked = true;
      cause = "unknown_rapid_restarts";
    }
  }

  void arm(int pid, long now) {
    if (blocked) throw new IllegalStateException("Crash protection is latched");
    launches.put(pid, now);
    while (launches.size() > 8) launches.remove(launches.keySet().iterator().next());
  }

  Properties encode() {
    Properties p = new Properties();
    p.setProperty("format", "1");
    p.setProperty("blocked", Boolean.toString(blocked));
    p.setProperty("boot", Integer.toString(boot));
    p.setProperty("observed", Long.toString(lastObserved));
    p.setProperty("reset", resetToken);
    p.setProperty("cause", cause);
    StringJoiner starts = new StringJoiner(";");
    launches.forEach((pid, time) -> starts.add(pid + ":" + time));
    p.setProperty("launches", starts.toString());
    StringJoiner times = new StringJoiner(";");
    crashes.forEach(time -> times.add(Long.toString(time)));
    p.setProperty("crashes", times.toString());
    return p;
  }

  static CrashLoopState decode(Properties p) {
    if (!"1".equals(p.getProperty("format")))
      throw new IllegalArgumentException("Guard state format");
    CrashLoopState state = new CrashLoopState();
    String blocked = p.getProperty("blocked");
    if (!"true".equals(blocked) && !"false".equals(blocked))
      throw new IllegalArgumentException("Guard latch");
    state.blocked = Boolean.parseBoolean(blocked);
    state.boot = Integer.parseInt(p.getProperty("boot"));
    state.lastObserved = Long.parseLong(p.getProperty("observed"));
    state.resetToken = p.getProperty("reset", "");
    state.cause = p.getProperty("cause", "");
    for (String entry : p.getProperty("launches", "").split(";")) {
      if (entry.isEmpty()) continue;
      String[] pair = entry.split(":");
      if (pair.length != 2) throw new IllegalArgumentException("Guard launch");
      state.launches.put(Integer.parseInt(pair[0]), Long.parseLong(pair[1]));
    }
    for (String time : p.getProperty("crashes", "").split(";"))
      if (!time.isEmpty()) state.crashes.add(Long.parseLong(time));
    if (state.launches.size() > 8 || state.crashes.size() > 32)
      throw new IllegalArgumentException("Guard bounds");
    return state;
  }
}
