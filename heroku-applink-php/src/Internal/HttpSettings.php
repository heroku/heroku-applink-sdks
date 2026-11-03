<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

/**
 * @internal
 */
final readonly class HttpSettings
{
    /**
     * @param list<float> $retryDelays
     * @param non-empty-string|null $caFile
     */
    public function __construct(
        public float $timeout = 5.0,
        public array $retryDelays = [0.25, 0.5, 1.0],
        public ?string $caFile = null,
    ) {}
}
