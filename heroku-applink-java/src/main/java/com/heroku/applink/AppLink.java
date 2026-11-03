package com.heroku.applink;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;

/**
 * The entry point of the Heroku AppLink SDK.
 *
 * <p>Use {@link #parseInvocationContext} to get the context of an invocation from Salesforce, and
 * {@link #getAuthorization(String)} to look up a stored authorization.
 *
 * @see <a href="https://devcenter.heroku.com/articles/heroku-applink">Heroku AppLink</a>
 */
public final class AppLink {
  static final String USER_AGENT = "heroku-applink-java/" + readVersion();

  private static final class DefaultSettings {
    static final Http.Settings INSTANCE = Http.Settings.withDefaults(HttpClient.newBuilder());
  }

  private AppLink() {}

  /**
   * Parses the context of an invocation from Salesforce from the {@code x-client-context} header of
   * an inbound request.
   *
   * <p>The SDK trusts the header, because AppLink's Service Mesh authenticates requests before they
   * reach the app.
   *
   * <pre>{@code
   * InvocationAppLinkContext context = AppLink.parseInvocationContext(request::getHeader);
   * }</pre>
   *
   * @param getHeader returns the value of the inbound request's header with the given name, or
   *     {@code null} if the request doesn't have it. The SDK always passes header names in lower
   *     case.
   * @return the context of the invocation
   * @throws InvalidInboundDataException if the header is missing or malformed
   * @see <a href="https://devcenter.heroku.com/articles/invoking-heroku-applink-apps">Invoking
   *     Heroku AppLink Apps</a>
   */
  public static InvocationAppLinkContext parseInvocationContext(
      Function<String, String> getHeader) {
    Objects.requireNonNull(getHeader, "getHeader");
    return InvocationContextParser.parse(getHeader);
  }

  /**
   * Looks up a stored authorization through the AppLink API, using the default attachment {@code
   * HEROKU_APPLINK}.
   *
   * <p>Every call sends a request to the AppLink API. Apps can keep the returned context and reuse
   * it until {@code credentials().expiration()}.
   *
   * @param developerName the developer name of the authorization
   * @return the context of the authorization
   * @throws ConfigurationException if the developer name is empty, or the config vars are missing
   *     or invalid
   * @throws TransportException if the request to the AppLink API didn't complete
   * @throws HttpException if the AppLink API responded with a status other than 2xx
   * @throws UnusableAuthorizationException if the authorization isn't authorized
   * @throws UnexpectedResponseException if the response doesn't have the expected content
   * @see <a href="https://devcenter.heroku.com/articles/heroku-applink-api">Heroku AppLink API</a>
   */
  public static AuthorizationAppLinkContext getAuthorization(String developerName) {
    Objects.requireNonNull(developerName, "developerName");
    return getAuthorization(System.getenv(), DefaultSettings.INSTANCE, developerName, null);
  }

  /**
   * Looks up a stored authorization through the AppLink API, using the given attachment.
   *
   * <p>Every call sends a request to the AppLink API. Apps can keep the returned context and reuse
   * it until {@code credentials().expiration()}.
   *
   * <pre>{@code
   * // By name, reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
   * AuthorizationAppLinkContext context =
   *     AppLink.getAuthorization("<my-authorization>", "HEROKU_APPLINK_PURPLE");
   *
   * // By color, reads the same config vars as the name
   * AuthorizationAppLinkContext context = AppLink.getAuthorization("<my-authorization>", "purple");
   *
   * // By API URL, uses the attachment whose _API_URL config var holds that URL
   * String apiUrl = System.getenv("HEROKU_APPLINK_PURPLE_API_URL");
   * AuthorizationAppLinkContext context = AppLink.getAuthorization("<my-authorization>", apiUrl);
   * }</pre>
   *
   * @param developerName the developer name of the authorization
   * @param attachment the AppLink add-on attachment to use, by name (such as {@code
   *     HEROKU_APPLINK_PURPLE}), color (such as {@code purple}), or API URL
   * @return the context of the authorization
   * @throws ConfigurationException if the developer name or attachment is empty, or the config vars
   *     are missing or invalid
   * @throws TransportException if the request to the AppLink API didn't complete
   * @throws HttpException if the AppLink API responded with a status other than 2xx
   * @throws UnusableAuthorizationException if the authorization isn't authorized
   * @throws UnexpectedResponseException if the response doesn't have the expected content
   * @see <a href="https://devcenter.heroku.com/articles/heroku-applink-api">Heroku AppLink API</a>
   */
  public static AuthorizationAppLinkContext getAuthorization(
      String developerName, String attachment) {
    Objects.requireNonNull(developerName, "developerName");
    Objects.requireNonNull(attachment, "attachment");
    return getAuthorization(System.getenv(), DefaultSettings.INSTANCE, developerName, attachment);
  }

  static AuthorizationAppLinkContext getAuthorization(
      Map<String, String> env, Http.Settings settings, String developerName, String attachment) {
    if (developerName.isEmpty()) {
      throw new ConfigurationException("The developer name must not be empty.");
    }
    AttachmentResolver.ResolvedAttachment resolved = AttachmentResolver.resolve(env, attachment);
    URI uri = toUri(resolved.apiUrl() + AuthorizationResponseParser.path(developerName));

    Http.Response response =
        Http.getWithRetries(
            settings,
            uri,
            Map.of(
                "Authorization",
                "Bearer " + resolved.token(),
                "X-App-UUID",
                resolved.appId(),
                "Accept",
                "application/json",
                "User-Agent",
                USER_AGENT));

    if (response.status() < 200 || response.status() > 299) {
      throw new HttpException(
          "The AppLink API responded with HTTP status " + response.status() + ".",
          response.status(),
          new String(response.body(), StandardCharsets.UTF_8));
    }
    if (response.bodyTooLarge()) {
      throw new UnexpectedResponseException("The AppLink API response is larger than 1 MiB.");
    }
    return AuthorizationResponseParser.parse(response.body());
  }

  private static URI toUri(String url) {
    URI uri = null;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      uri = null;
    }
    if (uri == null || uri.getHost() == null) {
      throw new ConfigurationException("The AppLink API URL is not a valid URL.");
    }
    return uri;
  }

  private static String readVersion() {
    try (InputStream stream = AppLink.class.getResourceAsStream("version.properties")) {
      Properties properties = new Properties();
      properties.load(Objects.requireNonNull(stream, "version.properties"));
      return properties.getProperty("version");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
