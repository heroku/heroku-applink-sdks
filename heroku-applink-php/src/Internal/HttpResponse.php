<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

/**
 * @internal
 */
final readonly class HttpResponse
{
    public function __construct(
        public int $status,
        #[\SensitiveParameter]
        public string $body,
        public bool $bodyTooLarge,
    ) {}
}
