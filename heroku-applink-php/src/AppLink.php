<?php

declare(strict_types=1);

namespace Heroku\AppLink;

use Heroku\AppLink\Internal\AttachmentResolver;
use Heroku\AppLink\Internal\AuthorizationResponseParser;
use Heroku\AppLink\Internal\ConfigVars;
use Heroku\AppLink\Internal\Http;
use Heroku\AppLink\Internal\HttpSettings;
use Heroku\AppLink\Internal\InvocationContextParser;

/**
 * The entry point of the Heroku AppLink SDK.
 *
 * Use {@see AppLink::parseInvocationContext()} to get the context of an invocation from Salesforce,
 * and {@see AppLink::getAuthorization()} to look up a stored authorization.
 *
 * @see https://devcenter.heroku.com/articles/heroku-applink
 */
final class AppLink
{
    public const VERSION = '0.1.0';
    public const USER_AGENT = 'heroku-applink-php/' . self::VERSION;

    private function __construct() {}

    /**
     * Parses the context of an invocation from Salesforce from the `x-client-context` header of an
     * inbound request.
     *
     * The SDK trusts the header, because AppLink's Service Mesh authenticates requests before they
     * reach the app.
     *
     * ```php
     * $context = AppLink::parseInvocationContext(fn (string $name) => $request->getHeaderLine($name));
     * ```
     *
     * @param callable(string): ?string $getHeader Returns the value of the inbound request's header with
     *     the given name, or null if the request doesn't have it. The SDK always passes header names in
     *     lower case.
     *
     * @throws InvalidInboundDataException If the header is missing or malformed.
     *
     * @see https://devcenter.heroku.com/articles/invoking-heroku-applink-apps
     */
    public static function parseInvocationContext(callable $getHeader): InvocationAppLinkContext
    {
        return InvocationContextParser::parse($getHeader);
    }

    /**
     * Looks up a stored authorization through the AppLink API.
     *
     * Every call sends a request to the AppLink API. Apps can keep the returned context and reuse
     * it until `$context->credentials->expiration`.
     *
     * ```php
     * $context = AppLink::getAuthorization('<my-authorization>');
     * ```
     *
     * With another attachment:
     *
     * ```php
     * // By name, reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
     * $context = AppLink::getAuthorization('<my-authorization>', 'HEROKU_APPLINK_PURPLE');
     *
     * // By color, reads the same config vars as the name
     * $context = AppLink::getAuthorization('<my-authorization>', 'purple');
     *
     * // By API URL, uses the attachment whose _API_URL config var holds that URL
     * $apiUrl = getenv('HEROKU_APPLINK_PURPLE_API_URL');
     * $context = AppLink::getAuthorization('<my-authorization>', $apiUrl);
     * ```
     *
     * @param string $developerName The developer name of the authorization.
     * @param string|null $attachment The AppLink add-on attachment to use, by name (such as
     *     `HEROKU_APPLINK_PURPLE`), color (such as `purple`), or API URL. Defaults to `HEROKU_APPLINK`.
     *
     * @throws ConfigurationException If the developer name or attachment is empty, or the config vars
     *     are missing or invalid.
     * @throws TransportException If the request to the AppLink API didn't complete.
     * @throws HttpException If the AppLink API responded with a status other than 2xx.
     * @throws UnusableAuthorizationException If the authorization isn't authorized.
     * @throws UnexpectedResponseException If the response doesn't have the expected content.
     *
     * @see https://devcenter.heroku.com/articles/heroku-applink-api
     */
    public static function getAuthorization(string $developerName, ?string $attachment = null): AuthorizationAppLinkContext
    {
        return self::getAuthorizationWith(
            ConfigVars::read(getenv(), $_ENV, $_SERVER),
            new HttpSettings(),
            $developerName,
            $attachment,
        );
    }

    /**
     * @internal
     *
     * @param array<string, string> $env
     */
    public static function getAuthorizationWith(
        #[\SensitiveParameter]
        array $env,
        HttpSettings $settings,
        string $developerName,
        ?string $attachment = null,
    ): AuthorizationAppLinkContext {
        if ($developerName === '') {
            throw new ConfigurationException('The developer name must not be empty.');
        }
        $resolved = AttachmentResolver::resolve($env, $attachment);
        $response = Http::getWithRetries(
            $settings,
            $resolved->apiUrl . AuthorizationResponseParser::path($developerName),
            [
                'Authorization: Bearer ' . $resolved->token,
                'X-App-UUID: ' . $resolved->appId,
                'Accept: application/json',
                'User-Agent: ' . self::USER_AGENT,
            ],
        );

        if ($response->status < 200 || $response->status > 299) {
            throw new HttpException(
                "The AppLink API responded with HTTP status {$response->status}.",
                $response->status,
                Http::decodeErrorBody($response->body),
            );
        }
        if ($response->bodyTooLarge) {
            throw new UnexpectedResponseException('The AppLink API response is larger than 1 MiB.');
        }
        return AuthorizationResponseParser::parse($response->body);
    }
}
