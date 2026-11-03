<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * Data sent to the app is missing or can't be parsed, for example a missing or malformed
 * `x-client-context` header.
 */
final class InvalidInboundDataException extends AppLinkException {}
