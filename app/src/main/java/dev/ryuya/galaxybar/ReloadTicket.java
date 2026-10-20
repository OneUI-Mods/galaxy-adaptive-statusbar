package dev.ryuya.galaxybar;

import java.util.Properties;

/** Protocol 1: a short-lived request in SystemUI's private, owner-only storage. */
record ReloadTicket(int boot, int user, long created, String nonce) {
  static final int PROTOCOL = 1;
  static final long REQUEST_LIFETIME_MS = 12_000;
  static final String REQUEST = "galaxy-reload-request.properties";
  static final String SUPPORT = "galaxy-reload-support.properties";
  static final String RESULT = "galaxy-reload-result.properties";
  static final String CANCEL = "galaxy-reload-cancel.properties";

  static ReloadTicket parse(Properties properties) {
    try {
      if (!Integer.toString(PROTOCOL).equals(properties.getProperty("protocol"))) return null;
      String nonce = properties.getProperty("nonce", "");
      if (!nonce.matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return null;
      return new ReloadTicket(
          Integer.parseInt(properties.getProperty("boot")),
          Integer.parseInt(properties.getProperty("user")),
          Long.parseLong(properties.getProperty("created")),
          nonce);
    } catch (RuntimeException malformed) {
      return null;
    }
  }

  boolean accepts(
      int currentBoot, int currentUser, long now, boolean interactive, boolean userUnlocked) {
    return boot >= 0
        && boot == currentBoot
        && user == 0
        && user == currentUser
        && created >= 0
        && now >= created
        && now - created <= REQUEST_LIFETIME_MS
        && interactive
        && userUnlocked;
  }

  Properties properties() {
    Properties result = new Properties();
    result.setProperty("protocol", Integer.toString(PROTOCOL));
    result.setProperty("boot", Integer.toString(boot));
    result.setProperty("user", Integer.toString(user));
    result.setProperty("created", Long.toString(created));
    result.setProperty("nonce", nonce);
    return result;
  }
}
