<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The AppLink API responded with a status other than 2xx.
 */
final class HttpException extends AppLinkException
{
    /**
     * @param int $status The HTTP status of the response, such as `404` for an unknown authorization.
     * @param string $body The response body as UTF-8 text, with invalid bytes replaced. At most 1 MiB is read.
     */
    public function __construct(
        string $message,
        public readonly int $status,
        public readonly string $body,
    ) {
        parent::__construct($message);
    }
}
