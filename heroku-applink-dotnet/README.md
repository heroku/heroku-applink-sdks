# Heroku AppLink SDK for .NET

The Heroku AppLink SDK lets Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

## Installation

```sh
dotnet add package Heroku.AppLink
```

Requires .NET 10 or newer.

## Handling invocations from Salesforce

When Salesforce invokes your app through AppLink, `HerokuAppLink.ParseInvocationContext` decodes the `x-client-context` header of the request. For example, in an ASP.NET Core minimal API:

```csharp
using Heroku.AppLink;

var app = WebApplication.Create(args);

app.MapPost("/accounts", (HttpRequest request) =>
{
    InvocationAppLinkContext context;
    try
    {
        context = HerokuAppLink.ParseInvocationContext(name => request.Headers[name]);
    }
    catch (InvalidInboundDataException)
    {
        return Results.BadRequest("Not an AppLink request");
    }

    app.Logger.LogInformation("Invoked by {User} in org {Org}", context.User.Username, context.Org.Id);
    return Results.Ok(new { context.RequestId });
});

app.Run();
```

`context.Credentials` holds an access token for the invoking user if the invocation carries one.

## Looking up an authorization

`HerokuAppLink.GetAuthorizationAsync` looks up a stored authorization through the AppLink API. Pass the org and credentials to a Salesforce client, or call the Salesforce REST API directly:

```csharp
using System.Net.Http.Headers;
using Heroku.AppLink;

var context = await HerokuAppLink.GetAuthorizationAsync("<my-authorization>");

using var salesforce = new HttpClient { BaseAddress = new Uri(context.Org.DomainUrl) };
salesforce.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
    "Bearer",
    context.Credentials.AccessToken
);

var query = Uri.EscapeDataString("SELECT Id, Name FROM Account");
var accounts = await salesforce.GetStringAsync(
    $"/services/data/v{context.Org.ApiVersion}/query?q={query}"
);
```

To use another AppLink add-on attachment than the default `HEROKU_APPLINK`, pass its name, color, or API URL:

```csharp
// By name, reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
var context = await HerokuAppLink.GetAuthorizationAsync("<my-authorization>", attachment: "HEROKU_APPLINK_PURPLE");

// By color, reads the same config vars as the name
var context = await HerokuAppLink.GetAuthorizationAsync("<my-authorization>", attachment: "purple");

// By API URL, uses the attachment whose _API_URL config var holds that URL
var apiUrl = Environment.GetEnvironmentVariable("HEROKU_APPLINK_PURPLE_API_URL")
    ?? throw new InvalidOperationException("HEROKU_APPLINK_PURPLE_API_URL is not set.");
var context = await HerokuAppLink.GetAuthorizationAsync("<my-authorization>", attachment: apiUrl);
```

An API URL must match the value of exactly one of the app's config vars ending in `_API_URL`.

Every lookup sends a request to the AppLink API. You can keep a context and reuse it until `context.Credentials.Expiration`, then look it up again for a new access token.

## Errors

All exceptions extend `AppLinkException`. Canceling the `CancellationToken` throws an `OperationCanceledException` instead.

| Exception                        | Cause                                                             |
| -------------------------------- | ----------------------------------------------------------------- |
| `InvalidInboundDataException`    | The `x-client-context` header is missing or malformed.            |
| `ConfigurationException`         | The input or the AppLink config vars are missing or invalid.      |
| `TransportException`             | The request to the AppLink API didn't complete.                   |
| `HttpException`                  | The AppLink API responded with a non-2xx `Status`, such as `404`. |
| `UnusableAuthorizationException` | The authorization's `Status` isn't `Authorized`.                  |
| `UnexpectedResponseException`    | The AppLink API response doesn't have the expected content.       |

## Development

```sh
dotnet test
dotnet format
```

`dotnet build` runs the .NET analyzers and treats their warnings as errors. The tests read the shared test vectors in [`../test-vectors`](../test-vectors).
