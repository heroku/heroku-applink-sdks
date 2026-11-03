<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * A request to the AppLink API didn't complete, for example because the connection failed or
 * timed out.
 */
final class TransportException extends AppLinkException {}
