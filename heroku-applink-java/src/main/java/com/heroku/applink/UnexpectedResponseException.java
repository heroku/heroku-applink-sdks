package com.heroku.applink;

/**
 * The AppLink API responded with a 2xx status, but its content doesn't match what the SDK expects,
 * such as a missing required field.
 */
public final class UnexpectedResponseException extends AppLinkException {
  private static final long serialVersionUID = 1L;

  UnexpectedResponseException(String message) {
    super(message);
  }
}
