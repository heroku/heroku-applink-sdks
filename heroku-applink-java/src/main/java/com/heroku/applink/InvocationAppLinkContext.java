package com.heroku.applink;

import java.util.Objects;
import java.util.Optional;

/**
 * The context of an invocation from Salesforce, decoded from the {@code x-client-context} header by
 * {@link AppLink#parseInvocationContext}.
 *
 * <p>There is one per inbound request. It describes the invoking user.
 *
 * @param org the invoking org
 * @param user the invoking user
 * @param requestId the ID of the invocation
 * @param namespace the invoking org's namespace prefix; empty when the org has none
 * @param credentials credentials for acting as the invoking user; empty when the invocation carries
 *     no access token
 */
public record InvocationAppLinkContext(
    Org org,
    User user,
    String requestId,
    Optional<String> namespace,
    Optional<Credentials> credentials)
    implements AppLinkContext {
  /**
   * Creates an invocation context.
   *
   * @param org the invoking org
   * @param user the invoking user
   * @param requestId the ID of the invocation
   * @param namespace the invoking org's namespace prefix
   * @param credentials credentials for acting as the invoking user
   */
  public InvocationAppLinkContext {
    Objects.requireNonNull(org, "org");
    Objects.requireNonNull(user, "user");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(namespace, "namespace");
    Objects.requireNonNull(credentials, "credentials");
  }
}
