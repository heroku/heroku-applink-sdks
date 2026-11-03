<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The context of an invocation from Salesforce.
 */
final readonly class InvocationAppLinkContext extends AppLinkContext
{
    /**
     * @param Org $org The org the invocation comes from.
     * @param User $user The user who invoked the app.
     * @param string $requestId The ID of the invocation.
     * @param string|null $namespace The invoking org's namespace prefix, or null if the org has none.
     * @param Credentials|null $credentials Credentials for the invoking user, or null if the invocation
     *     doesn't carry any.
     */
    public function __construct(
        Org $org,
        User $user,
        public string $requestId,
        public ?string $namespace = null,
        public ?Credentials $credentials = null,
    ) {
        parent::__construct($org, $user);
    }
}
