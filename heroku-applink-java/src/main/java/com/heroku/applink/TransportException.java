package com.heroku.applink;

/**
 * A request to the AppLink API didn't complete, for example because the connection failed or timed
 * out.
 */
public final class TransportException extends AppLinkException {
  private static final long serialVersionUID = 1L;

  TransportException(String message, Throwable cause) {
    super(message, cause);
  }
}
