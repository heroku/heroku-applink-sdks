package com.heroku.applink;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Metadata of an authorization, as returned by the AppLink API.
 *
 * @param id the ID of the authorization
 * @param developerName the developer name of the authorization
 * @param createdAt the instant the authorization was created
 * @param createdBy who created the authorization
 * @param createdViaApp the app the authorization was created through, if any
 * @param lastModifiedAt the instant the authorization was last modified
 * @param lastModifiedBy who last modified the authorization, if known
 * @param redirectUri the redirect URI of the authorization, if any
 * @see <a href="https://devcenter.heroku.com/articles/heroku-applink-api">Heroku AppLink API</a>
 */
public record AuthorizationMetadata(
    String id,
    String developerName,
    Instant createdAt,
    String createdBy,
    Optional<String> createdViaApp,
    Instant lastModifiedAt,
    Optional<String> lastModifiedBy,
    Optional<String> redirectUri) {
  /**
   * Creates authorization metadata.
   *
   * @param id the ID of the authorization
   * @param developerName the developer name of the authorization
   * @param createdAt the instant the authorization was created
   * @param createdBy who created the authorization
   * @param createdViaApp the app the authorization was created through
   * @param lastModifiedAt the instant the authorization was last modified
   * @param lastModifiedBy who last modified the authorization
   * @param redirectUri the redirect URI of the authorization
   */
  public AuthorizationMetadata {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(developerName, "developerName");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(createdViaApp, "createdViaApp");
    Objects.requireNonNull(lastModifiedAt, "lastModifiedAt");
    Objects.requireNonNull(lastModifiedBy, "lastModifiedBy");
    Objects.requireNonNull(redirectUri, "redirectUri");
  }
}
