<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * A Salesforce user.
 */
final readonly class User
{
    /**
     * @param string $id The ID of the user.
     * @param string $username The username of the user.
     */
    public function __construct(
        public string $id,
        public string $username,
    ) {}
}
