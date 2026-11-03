package com.heroku.applink;

import java.util.Objects;

/**
 * The context of a stored authorization, returned by {@link AppLink#getAuthorization(String)}.
 *
 * <p>It describes the user the authorization was made for. It is long-lived, and apps can cache and
 * reuse it until {@code credentials().expiration()}.
 *
 * @param org the org of the authorization
 * @param user the authorized user
 * @param authorizationMetadata the metadata of the authorization
 * @param credentials credentials for acting as the authorized user
 */
public record AuthorizationAppLinkContext(
    Org org, User user, AuthorizationMetadata authorizationMetadata, Credentials credentials)
    implements AppLinkContext {
  /**
   * Creates an authorization context.
   *
   * @param org the org of the authorization
   * @param user the authorized user
   * @param authorizationMetadata the metadata of the authorization
   * @param credentials credentials for acting as the authorized user
   */
  public AuthorizationAppLinkContext {
    Objects.requireNonNull(org, "org");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(authorizationMetadata, "authorizationMetadata");
    Objects.requireNonNull(credentials, "credentials");
  }
}
