<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The org and user an app acts for.
 */
abstract readonly class AppLinkContext
{
    /**
     * @param Org $org The Salesforce org.
     * @param User $user The Salesforce user.
     */
    public function __construct(
        public Org $org,
        public User $user,
    ) {}
}
