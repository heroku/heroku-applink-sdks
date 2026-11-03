package com.heroku.applink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

final class TlsServer implements AutoCloseable {
  private static final char[] PASSWORD = "changeit".toCharArray();

  @FunctionalInterface
  interface Handler {
    void handle(HttpExchange exchange) throws IOException;
  }

  record Request(String method, String rawPath, Map<String, List<String>> headers) {
    String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .map(e -> e.getValue().getFirst())
          .findFirst()
          .orElse(null);
    }
  }

  final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
  final SSLContext clientContext;
  private final List<Handler> handlers = Collections.synchronizedList(new ArrayList<>());
  private final HttpsServer server;
  private final ExecutorService executor = Executors.newCachedThreadPool();

  TlsServer() throws Exception {
    Path directory = Files.createTempDirectory("applink-test");
    Path keyStoreFile = directory.resolve("server.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "server",
                "-keyalg",
                "EC",
                "-groupname",
                "secp256r1",
                "-dname",
                "CN=localhost",
                "-ext",
                "san=dns:localhost,ip:127.0.0.1",
                "-validity",
                "1",
                "-storetype",
                "PKCS12",
                "-keystore",
                keyStoreFile.toString(),
                "-storepass",
                new String(PASSWORD))
            .redirectErrorStream(true)
            .start();
    String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (keytool.waitFor() != 0) {
      throw new IllegalStateException("keytool failed: " + output);
    }

    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream stream = Files.newInputStream(keyStoreFile)) {
      keyStore.load(stream, PASSWORD);
    }
    Files.delete(keyStoreFile);
    Files.delete(directory);

    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(keyStore, PASSWORD);
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(keyManagers.getKeyManagers(), null, null);

    KeyStore trustStore = KeyStore.getInstance("PKCS12");
    trustStore.load(null, null);
    trustStore.setCertificateEntry("server", keyStore.getCertificate("server"));
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(trustStore);
    clientContext = SSLContext.getInstance("TLS");
    clientContext.init(null, trustManagers.getTrustManagers(), null);

    server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.setExecutor(executor);
    server.createContext(
        "/",
        exchange -> {
          requests.add(
              new Request(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().getRawPath(),
                  Map.copyOf(exchange.getRequestHeaders())));
          Handler handler;
          synchronized (handlers) {
            handler = handlers.size() > 1 ? handlers.removeFirst() : handlers.getFirst();
          }
          try (exchange) {
            handler.handle(exchange);
          }
        });
    server.start();
  }

  String url() {
    return "https://localhost:" + server.getAddress().getPort();
  }

  HttpClient.Builder clientBuilder() {
    return HttpClient.newBuilder().sslContext(clientContext);
  }

  void respond(Handler... responses) {
    synchronized (handlers) {
      handlers.clear();
      handlers.addAll(List.of(responses));
    }
    requests.clear();
  }

  static Handler respondWith(int status, String body) {
    return respondWith(status, body.getBytes(StandardCharsets.UTF_8), Map.of());
  }

  static Handler respondWith(int status, byte[] body, Map<String, String> headers) {
    return exchange -> {
      headers.forEach(exchange.getResponseHeaders()::set);
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      exchange.getResponseBody().write(body);
    };
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
