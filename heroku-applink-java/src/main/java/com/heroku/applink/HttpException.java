package com.heroku.applink;

/** The AppLink API responded with a status other than 2xx. */
public final class HttpException extends AppLinkException {
  private static final long serialVersionUID = 1L;

  /** The HTTP status of the response. */
  private final int status;

  /** The response body. */
  private final String body;

  HttpException(String message, int status, String body) {
    super(message);
    this.status = status;
    this.body = body;
  }

  /**
   * Returns the HTTP status of the response, such as {@code 404} for an unknown authorization.
   *
   * @return the HTTP status
   */
  public int status() {
    return status;
  }

  /**
   * Returns the response body as UTF-8 text, with invalid bytes replaced. At most 1 MiB is read.
   *
   * @return the response body
   */
  public String body() {
    return body;
  }
}
