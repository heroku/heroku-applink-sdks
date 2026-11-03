<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * An authorization lookup can't start, because its input or the app's config vars are missing or
 * invalid. Thrown before any request is sent.
 */
final class ConfigurationException extends AppLinkException {}
