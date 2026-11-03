package com.heroku.applink;

/**
 * The AppLink API returned the authorization, but it isn't {@link AuthorizationStatus#AUTHORIZED},
 * for example because it is still being authorized or was disconnected.
 */
public final class UnusableAuthorizationException extends AppLinkException {
  private static final long serialVersionUID = 1L;

  /** The status of the authorization. */
  private final AuthorizationStatus status;

  UnusableAuthorizationException(String message, AuthorizationStatus status) {
    super(message);
    this.status = status;
  }

  /**
   * Returns the status of the authorization.
   *
   * @return the status
   */
  public AuthorizationStatus status() {
    return status;
  }
}
