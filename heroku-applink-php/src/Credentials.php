<?php

declare(strict_types=1);

namespace Heroku\AppLink;

use DateTimeImmutable;

/**
 * Credentials for calling Salesforce APIs.
 *
 * `var_dump()` and `print_r()` redact the access token, so it doesn't leak into logs by accident.
 */
final readonly class Credentials
{
    /**
     * @param string $accessToken The Salesforce access token. Pass it to a Salesforce client as the bearer token.
     * @param DateTimeImmutable|null $expiration The instant the access token expires, in UTC, or null if
     *     AppLink doesn't provide one.
     */
    public function __construct(
        #[\SensitiveParameter]
        public string $accessToken,
        public ?DateTimeImmutable $expiration = null,
    ) {}

    // var_export() isn't covered on purpose: it serializes to PHP code, and serialization has to
    // include the access token.
    /**
     * @return array{accessToken: string, expiration: DateTimeImmutable|null}
     */
    public function __debugInfo(): array
    {
        return ['accessToken' => '[REDACTED]', 'expiration' => $this->expiration];
    }
}
