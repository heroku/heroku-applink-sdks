package com.heroku.applink;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Credentials for calling Salesforce APIs.
 *
 * <p>{@link #toString()} redacts the access token, so it doesn't leak into logs by accident.
 *
 * @param accessToken the Salesforce access token; pass it to a Salesforce client as the bearer
 *     token
 * @param expiration the instant the access token expires; empty if AppLink doesn't provide one
 */
public record Credentials(String accessToken, Optional<Instant> expiration) {
  /**
   * Creates credentials.
   *
   * @param accessToken the Salesforce access token
   * @param expiration the instant the access token expires, if known
   */
  public Credentials {
    Objects.requireNonNull(accessToken, "accessToken");
    Objects.requireNonNull(expiration, "expiration");
  }

  /**
   * Returns a representation of the credentials with the access token redacted.
   *
   * @return the redacted representation
   */
  @Override
  public String toString() {
    return "Credentials[accessToken=[REDACTED], expiration=" + expiration + "]";
  }
}
