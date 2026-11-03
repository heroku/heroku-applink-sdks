# Heroku AppLink SDK for PHP

The Heroku AppLink SDK lets Heroku apps find out who invoked them from Salesforce, and look up the credentials of stored [Heroku AppLink](https://devcenter.heroku.com/articles/heroku-applink) authorizations. Apps call Salesforce APIs with the Salesforce client of their choice, using the org and credentials the SDK returns.

## Installation

```sh
composer require heroku/applink
```

Requires PHP 8.4 or newer with the `curl` and `mbstring` extensions.

## Handling invocations from Salesforce

When Salesforce invokes your app through AppLink, `AppLink::parseInvocationContext` decodes the `x-client-context` header of the request. It works with any framework, for example with a [PSR-7](https://www.php-fig.org/psr/psr-7/) request:

```php
use Heroku\AppLink\AppLink;
use Heroku\AppLink\InvalidInboundDataException;
use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;

function accounts(ServerRequestInterface $request, ResponseInterface $response): ResponseInterface
{
    try {
        $context = AppLink::parseInvocationContext(fn (string $name) => $request->getHeaderLine($name));
    } catch (InvalidInboundDataException) {
        return $response->withStatus(400);
    }

    error_log("Invoked by {$context->user->username} in org {$context->org->id}");
    $response->getBody()->write(json_encode(['requestId' => $context->requestId]));
    return $response->withHeader('Content-Type', 'application/json');
}
```

`$context->credentials` holds an access token for the invoking user if the invocation carries one.

## Looking up an authorization

`AppLink::getAuthorization` looks up a stored authorization through the AppLink API. Use the org and credentials to call Salesforce APIs, for example the REST API with [Guzzle](https://docs.guzzlephp.org/):

```php
use GuzzleHttp\Client;
use Heroku\AppLink\AppLink;

$context = AppLink::getAuthorization('<my-authorization>');

$salesforce = new Client([
    'base_uri' => "{$context->org->domainUrl}/services/data/v{$context->org->apiVersion}/",
    'headers' => ['Authorization' => "Bearer {$context->credentials->accessToken}"],
]);

$result = json_decode((string) $salesforce->get('query', [
    'query' => ['q' => 'SELECT Id, Name FROM Account'],
])->getBody(), true);
```

To use another AppLink add-on attachment than the default `HEROKU_APPLINK`, pass its name, color, or API URL:

```php
// By name, reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
$context = AppLink::getAuthorization('<my-authorization>', 'HEROKU_APPLINK_PURPLE');

// By color, reads the same config vars as the name
$context = AppLink::getAuthorization('<my-authorization>', 'purple');

// By API URL, uses the attachment whose _API_URL config var holds that URL
$apiUrl = getenv('HEROKU_APPLINK_PURPLE_API_URL');
$context = AppLink::getAuthorization('<my-authorization>', $apiUrl);
```

An API URL must match the value of exactly one of the app's config vars ending in `_API_URL`.

The SDK reads the config vars from the process environment, `$_ENV`, and `$_SERVER`, in that order, so values set with `fastcgi_param`, Apache's `SetEnv`, or a dotenv library work too.

Every lookup sends a request to the AppLink API. You can keep a context and reuse it until `$context->credentials->expiration`, then look it up again for a new access token.

## Errors

All exceptions extend `Heroku\AppLink\AppLinkException`:

| Exception                        | Cause                                                              |
| -------------------------------- | ------------------------------------------------------------------ |
| `InvalidInboundDataException`    | The `x-client-context` header is missing or malformed.             |
| `ConfigurationException`         | The input or the AppLink config vars are missing or invalid.       |
| `TransportException`             | The request to the AppLink API didn't complete.                    |
| `HttpException`                  | The AppLink API responded with a non-2xx `$status`, such as `404`. |
| `UnusableAuthorizationException` | The authorization's `$status` isn't `Authorized`.                  |
| `UnexpectedResponseException`    | The AppLink API response doesn't have the expected content.        |

## Development

```sh
composer install
composer test
composer lint
composer format
```

The tests read the shared test vectors in [`../test-vectors`](../test-vectors). They need the `openssl` and `pcntl` extensions to run a local HTTPS server.
