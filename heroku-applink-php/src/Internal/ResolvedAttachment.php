<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

/**
 * @internal
 */
final readonly class ResolvedAttachment
{
    public function __construct(
        public string $apiUrl,
        #[\SensitiveParameter]
        public string $token,
        public string $appId,
    ) {}

    /**
     * @return array{apiUrl: string, token: string, appId: string}
     */
    public function __debugInfo(): array
    {
        return ['apiUrl' => $this->apiUrl, 'token' => '[REDACTED]', 'appId' => $this->appId];
    }
}
