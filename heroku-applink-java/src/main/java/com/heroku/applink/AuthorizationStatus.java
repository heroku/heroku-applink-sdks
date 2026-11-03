package com.heroku.applink;

/**
 * The status of an authorization, as returned by the AppLink API.
 *
 * <p>Only {@link #AUTHORIZED} authorizations can be used. Apps only see the other statuses in an
 * {@link UnusableAuthorizationException}.
 *
 * @see <a href="https://devcenter.heroku.com/articles/heroku-applink-api">Heroku AppLink API</a>
 */
public enum AuthorizationStatus {
  /** The add-on is authorizing the user ({@code authorizing}). */
  AUTHORIZING("authorizing"),
  /** The user is authorized and the authorization can be used ({@code authorized}). */
  AUTHORIZED("authorized"),
  /** The add-on is removing the authorization ({@code disconnecting}). */
  DISCONNECTING("disconnecting"),
  /** The authorization has been removed ({@code disconnected}). */
  DISCONNECTED("disconnected"),
  /** Authorizing failed with an unexpected error ({@code failed}). */
  FAILED("failed");

  private final String value;

  AuthorizationStatus(String value) {
    this.value = value;
  }

  String value() {
    return value;
  }

  static AuthorizationStatus fromValue(String value) {
    for (AuthorizationStatus status : values()) {
      if (status.value.equals(value)) {
        return status;
      }
    }
    return null;
  }
}
