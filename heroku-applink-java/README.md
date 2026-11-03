# Heroku AppLink SDK for Java

The Heroku AppLink SDK lets Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

## Installation

```xml
<dependency>
  <groupId>com.heroku</groupId>
  <artifactId>heroku-applink</artifactId>
  <version>0.1.0</version>
</dependency>
```

Requires Java 21 or newer.

## Handling invocations from Salesforce

When Salesforce invokes your app through AppLink, `AppLink.parseInvocationContext` decodes the `x-client-context` header of the request. For example, in a Jakarta Servlet:

```java
import com.heroku.applink.AppLink;
import com.heroku.applink.InvalidInboundDataException;
import com.heroku.applink.InvocationAppLinkContext;

@Override
protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
  InvocationAppLinkContext context;
  try {
    context = AppLink.parseInvocationContext(request::getHeader);
  } catch (InvalidInboundDataException e) {
    response.sendError(400, "Not an AppLink request");
    return;
  }

  System.out.println("Invoked by " + context.user().username() + " in org " + context.org().id());
}
```

`context.credentials()` holds an access token for the invoking user if the invocation carries one.

## Looking up an authorization

`AppLink.getAuthorization` looks up a stored authorization through the AppLink API. Pass the org and credentials to a Salesforce client, or call the Salesforce REST API directly:

```java
import com.heroku.applink.AppLink;
import com.heroku.applink.AuthorizationAppLinkContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

AuthorizationAppLinkContext context = AppLink.getAuthorization("my-authorization");

String query = URLEncoder.encode("SELECT Id, Name FROM Account", StandardCharsets.UTF_8);
HttpRequest request =
    HttpRequest.newBuilder(
            URI.create(
                context.org().domainUrl()
                    + "/services/data/v"
                    + context.org().apiVersion()
                    + "/query?q="
                    + query))
        .header("Authorization", "Bearer " + context.credentials().accessToken())
        .build();

String accounts =
    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body();
```

To use another AppLink add-on attachment than the default `HEROKU_APPLINK`, pass its name, color, or API URL:

```java
AuthorizationAppLinkContext context = AppLink.getAuthorization("my-authorization", "purple");
```

Every lookup sends a request to the AppLink API. You can keep a context and reuse it until `context.credentials().expiration()`, then look it up again for a new access token.

## Errors

All exceptions are unchecked and extend `AppLinkException`:

| Exception                        | Cause                                                               |
| -------------------------------- | ------------------------------------------------------------------- |
| `InvalidInboundDataException`    | The `x-client-context` header is missing or malformed.              |
| `ConfigurationException`         | The input or the AppLink config vars are missing or invalid.        |
| `TransportException`             | The request to the AppLink API didn't complete.                     |
| `HttpException`                  | The AppLink API responded with a non-2xx `status()`, such as `404`. |
| `UnusableAuthorizationException` | The authorization's `status()` isn't `AUTHORIZED`.                  |
| `UnexpectedResponseException`    | The AppLink API response doesn't have the expected content.         |

## Development

```sh
mvn verify
mvn spotless:apply
```

`mvn verify` runs the tests, checks the formatting, and builds the JAR. The tests read the shared test vectors in [`../test-vectors`](../test-vectors).
