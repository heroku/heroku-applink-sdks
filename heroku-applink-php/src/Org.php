<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * A Salesforce org.
 */
final readonly class Org
{
    /**
     * @param string $id The ID of the org.
     * @param string $domainUrl The URL of the org's My Domain, such as `https://example.my.salesforce.com`.
     *     Use it as the instance URL of a Salesforce client.
     * @param string $apiVersion The Salesforce API version to use, such as `62.0`.
     * @param OrgType|null $type The type of the org, or null if it isn't known.
     */
    public function __construct(
        public string $id,
        public string $domainUrl,
        public string $apiVersion,
        public ?OrgType $type = null,
    ) {}
}
