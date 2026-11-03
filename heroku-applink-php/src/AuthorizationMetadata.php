<?php

declare(strict_types=1);

namespace Heroku\AppLink;

use DateTimeImmutable;

/**
 * Information about an AppLink authorization.
 */
final readonly class AuthorizationMetadata
{
    /**
     * @param string $id The ID of the authorization.
     * @param string $developerName The developer name of the authorization.
     * @param DateTimeImmutable $createdAt The instant the authorization was created, in UTC.
     * @param string $createdBy Who created the authorization.
     * @param string|null $createdViaApp The app the authorization was created with, if known.
     * @param DateTimeImmutable $lastModifiedAt The instant the authorization was last modified, in UTC.
     * @param string|null $lastModifiedBy Who last modified the authorization, if known.
     * @param string|null $redirectUri The redirect URI of the authorization, if any.
     */
    public function __construct(
        public string $id,
        public string $developerName,
        public DateTimeImmutable $createdAt,
        public string $createdBy,
        public ?string $createdViaApp,
        public DateTimeImmutable $lastModifiedAt,
        public ?string $lastModifiedBy,
        public ?string $redirectUri,
    ) {}
}
