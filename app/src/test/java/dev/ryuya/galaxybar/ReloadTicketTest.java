package dev.ryuya.galaxybar;

import static org.junit.Assert.*;

import java.util.Properties;
import org.junit.Test;

public class ReloadTicketTest {
  private final ReloadTicket ticket =
      new ReloadTicket(216, 0, 100_000, "8b759534-eefe-4ea5-a890-133951f8a9a1");

  @Test
  public void roundTripAcceptsOnlyTheCurrentUnlockedSession() {
    assertEquals(ticket, ReloadTicket.parse(ticket.properties()));
    assertTrue(ticket.accepts(216, 0, 100_000, true, true));
    assertTrue(ticket.accepts(216, 0, 112_000, true, true));
  }

  @Test
  public void expiredOrFutureRequestsCannotResume() {
    assertFalse(ticket.accepts(216, 0, 112_001, true, true));
    assertFalse(ticket.accepts(216, 0, 99_999, true, true));
  }

  @Test
  public void rebootUserSwitchOrScreenOffInvalidatesRequest() {
    assertFalse(ticket.accepts(217, 0, 100_001, true, true));
    assertFalse(ticket.accepts(216, 10, 100_001, true, true));
    assertFalse(ticket.accepts(216, 0, 100_001, false, true));
    assertFalse(ticket.accepts(216, 0, 100_001, true, false));
  }

  @Test
  public void malformedAndUnknownProtocolsAreRejected() {
    assertNull(ReloadTicket.parse(new Properties()));
    Properties p = ticket.properties();
    p.setProperty("protocol", "2");
    assertNull(ReloadTicket.parse(p));
    p = ticket.properties();
    p.setProperty("nonce", "invalid");
    assertNull(ReloadTicket.parse(p));
    p = ticket.properties();
    p.setProperty("created", "NaN");
    assertNull(ReloadTicket.parse(p));
  }
}
