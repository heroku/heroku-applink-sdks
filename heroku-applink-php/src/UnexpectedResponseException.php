<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The AppLink API responded with a 2xx status, but unexpected content, for example a response
 * with a missing required field.
 */
final class UnexpectedResponseException extends AppLinkException {}
