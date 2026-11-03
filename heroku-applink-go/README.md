# Heroku AppLink SDK for Go

The Heroku AppLink SDK lets Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

## Installation

```sh
go get github.com/heroku/heroku-applink-go
```

Requires Go 1.25 or newer.

## Handling invocations from Salesforce

When Salesforce invokes your app through AppLink, `applink.ParseInvocationContext` decodes the `x-client-context` header of the request. It takes a function that returns the value of the header with the given name, such as `Header.Get` of a `net/http` request:

```go
import applink "github.com/heroku/heroku-applink-go"

http.HandleFunc("POST /accounts", func(w http.ResponseWriter, r *http.Request) {
	invocation, err := applink.ParseInvocationContext(r.Header.Get)
	if err != nil {
		http.Error(w, "invalid x-client-context header", http.StatusBadRequest)
		return
	}

	log.Printf("Invoked by %s in org %s", invocation.User.Username, invocation.Org.ID)
	json.NewEncoder(w).Encode(map[string]string{"requestId": invocation.RequestID})
})
```

`invocation.Credentials` holds an access token for the invoking user if the invocation carries one, and is `nil` otherwise.

## Looking up an authorization

`applink.GetAuthorization` looks up a stored authorization through the AppLink API. Pass the org and credentials to a Salesforce client, or call the Salesforce REST API directly:

```go
authorization, err := applink.GetAuthorization(ctx, "my-authorization")
if err != nil {
	return err
}

request, err := http.NewRequestWithContext(ctx, http.MethodGet,
	authorization.Org.DomainURL+"/services/data/v"+authorization.Org.APIVersion+"/query?q=SELECT+Id,Name+FROM+Account", nil)
if err != nil {
	return err
}
request.Header.Set("Authorization", "Bearer "+authorization.Credentials.AccessToken)
response, err := http.DefaultClient.Do(request)
```

To use another AppLink add-on attachment than the default `HEROKU_APPLINK`, pass its name, color, or API URL:

```go
authorization, err := applink.GetAuthorization(ctx, "my-authorization", applink.WithAttachment("purple"))
```

Every lookup sends a request to the AppLink API. You can keep an authorization and reuse it until `authorization.Credentials.Expiration`, then look it up again for a new access token. Formatting credentials or a context with `fmt` and logging them with `slog.TextHandler` redacts the access token. So does logging the credentials themselves with `slog.JSONHandler`.

## Errors

Every error matches `applink.ErrAppLink` with `errors.Is`. Use `errors.As` to tell them apart:

| Error                         | Cause                                                               |
| ----------------------------- | ------------------------------------------------------------------- |
| `*InvalidInboundDataError`    | The `x-client-context` header is missing or malformed.              |
| `*ConfigurationError`         | The input or the AppLink config vars are missing or invalid.        |
| `*TransportError`             | The request to the AppLink API didn't complete.                     |
| `*HTTPError`                  | The AppLink API responded with a non-2xx `Status`, such as `404`.   |
| `*UnusableAuthorizationError` | The authorization's `Status` isn't `AuthorizationStatusAuthorized`. |
| `*UnexpectedResponseError`    | The AppLink API response doesn't have the expected content.         |

If `ctx` is canceled, `GetAuthorization` returns a `*TransportError` that wraps the context's error.

## Development

```sh
gofmt -l .
go vet ./...
go test ./...
```

The tests read the shared test vectors in [`../test-vectors`](../test-vectors).
