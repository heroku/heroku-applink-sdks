package com.heroku.applink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TypesTest {
  private static final Credentials CREDENTIALS =
      new Credentials("secret-token", Optional.of(Instant.parse("2026-10-13T09:15:42Z")));

  @Test
  void credentialsToStringRedactsTheAccessToken() {
    assertEquals(
        "Credentials[accessToken=[REDACTED], expiration=Optional[2026-10-13T09:15:42Z]]",
        CREDENTIALS.toString());
  }

  @Test
  void contextToStringRedactsTheAccessToken() {
    AppLinkContext context =
        new InvocationAppLinkContext(
            new Org("org", "https://example.tld", "62.0", Optional.empty()),
            new User("user", "user@example.tld"),
            "request",
            Optional.empty(),
            Optional.of(CREDENTIALS));
    assertFalse(context.toString().contains("secret-token"));
    assertTrue(context.toString().contains("[REDACTED]"));
  }

  @Test
  void resolvedAttachmentToStringRedactsTheToken() {
    String string =
        new AttachmentResolver.ResolvedAttachment("https://example.tld", "secret-token", "app")
            .toString();
    assertFalse(string.contains("secret-token"));
  }

  @Test
  void userAgentMatchesTheProjectVersion() {
    assertEquals("heroku-applink-java/" + System.getProperty("projectVersion"), AppLink.USER_AGENT);
  }
}
