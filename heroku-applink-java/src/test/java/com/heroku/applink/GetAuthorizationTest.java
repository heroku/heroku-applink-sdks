package com.heroku.applink;

import static com.heroku.applink.TlsServer.respondWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class GetAuthorizationTest {
  private static final int MIB = 1024 * 1024;
  private static final String VALID_BODY =
      Vectors.load("authorization-response.json").getFirst().get("body").getAsString();
  private static final Duration TIMEOUT = Duration.ofMillis(500);

  private static TlsServer server;
  private static HttpClient client;
  private static Http.Settings settings;
  private static Map<String, String> env;

  @BeforeAll
  static void startServer() throws Exception {
    server = new TlsServer();
    client = Http.Settings.withDefaults(server.clientBuilder()).client();
    settings =
        new Http.Settings(
            client,
            TIMEOUT,
            List.of(Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(3)));
    env =
        Map.of(
            "HEROKU_APPLINK_API_URL", server.url() + "/addons/abc/",
            "HEROKU_APPLINK_TOKEN", "addon-token",
            "HEROKU_APP_ID", "app-uuid");
  }

  @AfterAll
  static void stopServer() {
    client.close();
    server.close();
  }

  private static AuthorizationAppLinkContext get(Http.Settings settings, Map<String, String> env) {
    return AppLink.getAuthorization(env, settings, "x", null);
  }

  private static AuthorizationAppLinkContext get() {
    return get(settings, env);
  }

  @Test
  void sendsTheDocumentedRequest() {
    server.respond(respondWith(200, VALID_BODY));
    AuthorizationAppLinkContext context = AppLink.getAuthorization(env, settings, "my org/x", null);
    assertEquals("00DJS0000000123!AQ0AQ.token", context.credentials().accessToken());
    assertEquals(1, server.requests.size());
    TlsServer.Request request = server.requests.getFirst();
    assertEquals("GET", request.method());
    assertEquals("/addons/abc/authorizations/my%20org%2Fx", request.rawPath());
    assertEquals("Bearer addon-token", request.header("Authorization"));
    assertEquals("app-uuid", request.header("X-App-UUID"));
    assertEquals("application/json", request.header("Accept"));
    assertEquals(AppLink.USER_AGENT, request.header("User-Agent"));
  }

  @Test
  void configurationErrorsSendNoRequest() {
    server.respond(respondWith(200, VALID_BODY));
    assertThrows(
        ConfigurationException.class, () -> AppLink.getAuthorization(env, settings, "", null));
    assertThrows(
        ConfigurationException.class, () -> AppLink.getAuthorization(env, settings, "x", ""));
    Map<String, String> noAppId = new HashMap<>(env);
    noAppId.put("HEROKU_APP_ID", "");
    assertThrows(ConfigurationException.class, () -> get(settings, noAppId));
    assertEquals(0, server.requests.size());
  }

  @ParameterizedTest
  @CsvSource({
    "HEROKU_APPLINK_TOKEN, 'secret\ntoken'",
    "HEROKU_APPLINK_TOKEN, secret-tok\u20acn",
    "HEROKU_APP_ID, 'app-uuid\r\nX-Injected: secret'",
    "HEROKU_APPLINK_API_URL, https://127.0.0.1:1/a b",
    "HEROKU_APPLINK_API_URL, https:///addons/abc"
  })
  void configVarsTheRequestCantBeBuiltFrom(String name, String value) {
    server.respond(respondWith(200, VALID_BODY));
    Map<String, String> invalid = new HashMap<>(env);
    invalid.put(name, value);
    ConfigurationException e =
        assertThrows(ConfigurationException.class, () -> get(settings, invalid));
    assertFalse(e.getMessage().contains("secret"));
    assertNull(e.getCause());
    assertEquals(0, server.requests.size());
  }

  @Test
  void reportsStatusAndBody() {
    server.respond(respondWith(404, "{\"message\":\"not found\"}"));
    HttpException e = assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals(404, e.status());
    assertEquals("{\"message\":\"not found\"}", e.body());
    assertEquals(1, server.requests.size());
  }

  @Test
  void replacesInvalidUtf8InErrorBodies() {
    server.respond(respondWith(400, new byte[] {'a', (byte) 0xff, 'b'}, Map.of()));
    HttpException e = assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals("a�b", e.body());
  }

  @Test
  void doesntFollowRedirects() {
    server.respond(respondWith(302, new byte[0], Map.of("Location", "/elsewhere")));
    HttpException e = assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals(302, e.status());
    assertEquals(1, server.requests.size());
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 500, 503})
  void retries(int status) {
    server.respond(respondWith(status, "busy"), respondWith(200, VALID_BODY));
    get();
    assertEquals(2, server.requests.size());
  }

  @Test
  void reportsTheLastErrorAfterThreeRetries() {
    server.respond(
        respondWith(500, "first"),
        respondWith(500, "second"),
        respondWith(500, "third"),
        respondWith(503, "last"));
    HttpException e = assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals(503, e.status());
    assertEquals("last", e.body());
    assertEquals(4, server.requests.size());
  }

  @Test
  void doesntRetryOtherResponses() {
    server.respond(respondWith(400, "bad"), respondWith(200, VALID_BODY));
    assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals(1, server.requests.size());
  }

  @Test
  void doesntRetryUnusableAuthorizations() {
    server.respond(respondWith(200, "{\"status\":\"failed\"}"), respondWith(200, VALID_BODY));
    assertThrows(UnusableAuthorizationException.class, GetAuthorizationTest::get);
    assertEquals(1, server.requests.size());
  }

  @Test
  void retriesAfterTimeouts() {
    server.respond(exchange -> sleep(Duration.ofSeconds(1)));
    assertThrows(TransportException.class, GetAuthorizationTest::get);
    assertEquals(4, server.requests.size());
  }

  @Test
  void timesOutWhileReadingASlowBody() {
    server.respond(
        exchange -> {
          exchange.sendResponseHeaders(200, 100);
          for (int i = 0; i < 10; i++) {
            exchange.getResponseBody().write(' ');
            exchange.getResponseBody().flush();
            sleep(Duration.ofMillis(100));
          }
        });
    Http.Settings noRetries = new Http.Settings(client, TIMEOUT, List.of());
    assertThrows(TransportException.class, () -> get(noRetries, env));
  }

  @Test
  void connectionFailures() {
    Map<String, String> unreachable = new HashMap<>(env);
    unreachable.put("HEROKU_APPLINK_API_URL", "https://127.0.0.1:1");
    assertThrows(TransportException.class, () -> get(settings, unreachable));
  }

  @Test
  void untrustedCertificates() {
    server.respond(respondWith(200, VALID_BODY));
    try (HttpClient untrusting = HttpClient.newHttpClient()) {
      Http.Settings untrusted = new Http.Settings(untrusting, TIMEOUT, List.of());
      assertThrows(TransportException.class, () -> get(untrusted, env));
    }
  }

  @Test
  void rejects2xxBodiesLargerThan1Mib() {
    server.respond(respondWith(200, VALID_BODY + " ".repeat(MIB)));
    assertThrows(UnexpectedResponseException.class, GetAuthorizationTest::get);
  }

  @Test
  void accepts2xxBodiesOfExactly1Mib() {
    server.respond(respondWith(200, VALID_BODY + " ".repeat(MIB - VALID_BODY.length())));
    get();
  }

  @Test
  void truncatesErrorBodiesTo1Mib() {
    server.respond(respondWith(400, "x".repeat(2 * MIB)));
    HttpException e = assertThrows(HttpException.class, GetAuthorizationTest::get);
    assertEquals(MIB, e.body().length());
  }

  @Test
  void unexpectedResponsesDontLeakTheBody() {
    server.respond(
        respondWith(
            200,
            "{\"status\":\"authorized\",\"org\":{\"user_auth\":{\"access_token\":\"secret-token\"}}}"));
    UnexpectedResponseException e =
        assertThrows(UnexpectedResponseException.class, GetAuthorizationTest::get);
    assertFalse(e.getMessage().contains("secret-token"));
  }

  @Test
  void interruptedCallsFailWithTheInterruptFlagSet() {
    server.respond(respondWith(200, VALID_BODY));
    Thread.currentThread().interrupt();
    try {
      assertThrows(TransportException.class, GetAuthorizationTest::get);
      assert Thread.currentThread().isInterrupted();
    } finally {
      Thread.interrupted();
    }
  }

  private static void sleep(Duration duration) {
    try {
      Thread.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
