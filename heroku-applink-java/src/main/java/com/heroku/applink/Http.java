package com.heroku.applink;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class Http {
  static final int MAX_BODY_BYTES = 1024 * 1024;

  record Settings(HttpClient client, Duration timeout, List<Duration> retryDelays) {
    static Settings withDefaults(HttpClient.Builder builder) {
      Duration timeout = Duration.ofSeconds(5);
      return new Settings(
          builder.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build(),
          timeout,
          List.of(Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1)));
    }
  }

  record Response(int status, byte[] body, boolean bodyTooLarge) {}

  private Http() {}

  static Response getWithRetries(Settings settings, URI uri, Map<String, String> headers) {
    for (int attempt = 0; ; attempt++) {
      Response response = null;
      TransportException error = null;
      try {
        response = get(settings, uri, headers);
      } catch (TransportException e) {
        error = e;
      }

      boolean retryable =
          error != null
              ? !Thread.currentThread().isInterrupted()
              : response.status() == 429 || (response.status() >= 500 && response.status() <= 599);
      if (attempt >= settings.retryDelays().size() || !retryable) {
        if (error != null) {
          throw error;
        }
        return response;
      }

      try {
        Thread.sleep(settings.retryDelays().get(attempt));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new TransportException("Interrupted while waiting to retry the request.", e);
      }
    }
  }

  private static Response get(Settings settings, URI uri, Map<String, String> headers) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).GET().timeout(settings.timeout());
    try {
      headers.forEach(request::header);
    } catch (IllegalArgumentException e) {
      // Thrown without the cause, because its message can contain the token.
      throw new ConfigurationException("The config vars don't form a valid AppLink API request.");
    }

    CompletableFuture<HttpResponse<Response>> future =
        settings.client().sendAsync(request.build(), LimitedBodySubscriber::new);
    try {
      return future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS).body();
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new TransportException("The request to the AppLink API timed out.", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      throw new TransportException("The request to the AppLink API failed: " + cause, cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new TransportException("Interrupted while sending the request to the AppLink API.", e);
    }
  }

  private static final class LimitedBodySubscriber
      implements HttpResponse.BodySubscriber<Response> {
    private final int status;
    private final CompletableFuture<Response> result = new CompletableFuture<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private Flow.Subscription subscription;

    LimitedBodySubscriber(HttpResponse.ResponseInfo info) {
      this.status = info.statusCode();
    }

    @Override
    public CompletionStage<Response> getBody() {
      return result;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      subscription.request(Long.MAX_VALUE);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
      if (result.isDone()) {
        return;
      }
      for (ByteBuffer buffer : buffers) {
        byte[] bytes = new byte[Math.min(buffer.remaining(), MAX_BODY_BYTES + 1 - body.size())];
        buffer.get(bytes);
        body.writeBytes(bytes);
        if (body.size() > MAX_BODY_BYTES) {
          subscription.cancel();
          result.complete(
              new Response(status, Arrays.copyOf(body.toByteArray(), MAX_BODY_BYTES), true));
          return;
        }
      }
    }

    @Override
    public void onError(Throwable throwable) {
      result.completeExceptionally(throwable);
    }

    @Override
    public void onComplete() {
      result.complete(new Response(status, body.toByteArray(), false));
    }
  }
}
