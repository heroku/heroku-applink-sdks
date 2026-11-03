# Heroku AppLink SDK for Ruby

The Heroku AppLink SDK lets Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

## Installation

```sh
bundle add heroku-applink
```

Requires Ruby 3.4 or newer.

## Handling invocations from Salesforce

When Salesforce invokes your app through AppLink, `Heroku::AppLink.parse_invocation_context` decodes the `x-client-context` header of the request. The block returns the value of the header with the given name, for example in a Rails controller:

```ruby
require "heroku/applink"

class AccountsController < ApplicationController
  def create
    context = Heroku::AppLink.parse_invocation_context { |name| request.headers[name] }

    logger.info("Invoked by #{context.user.username} in org #{context.org.id}")
    render json: { requestId: context.request_id }
  rescue Heroku::AppLink::InvalidInboundDataError
    head :bad_request
  end
end
```

`context.credentials` holds an access token for the invoking user if the invocation carries one.

## Looking up an authorization

`Heroku::AppLink.get_authorization` looks up a stored authorization through the AppLink API. Pass the org and credentials to a Salesforce client, for example [Restforce](https://github.com/restforce/restforce):

```ruby
require "heroku/applink"
require "restforce"

context = Heroku::AppLink.get_authorization("my-authorization")

salesforce = Restforce.new(
  instance_url: context.org.domain_url,
  oauth_token: context.credentials.access_token,
  api_version: context.org.api_version
)

accounts = salesforce.query("SELECT Id, Name FROM Account")
```

To use another AppLink add-on attachment than the default `HEROKU_APPLINK`, pass its name, color, or API URL:

```ruby
context = Heroku::AppLink.get_authorization("my-authorization", attachment: "purple")
```

Every lookup sends a request to the AppLink API. You can keep a context and reuse it until `context.credentials.expiration`, then look it up again for a new access token.

## Errors

All errors are subclasses of `Heroku::AppLink::Error`:

| Error                        | Cause                                                             |
| ---------------------------- | ----------------------------------------------------------------- |
| `InvalidInboundDataError`    | The `x-client-context` header is missing or malformed.            |
| `ConfigurationError`         | The input or the AppLink config vars are missing or invalid.      |
| `TransportError`             | The request to the AppLink API didn't complete.                   |
| `HTTPError`                  | The AppLink API responded with a non-2xx `status`, such as `404`. |
| `UnusableAuthorizationError` | The authorization's `status` isn't `:authorized`.                 |
| `UnexpectedResponseError`    | The AppLink API response doesn't have the expected content.       |

## Development

```sh
bundle install
bundle exec rake test
bundle exec rubocop
```

The tests read the shared test vectors in [`../test-vectors`](../test-vectors).
