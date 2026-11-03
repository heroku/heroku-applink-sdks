<?php

declare(strict_types=1);

namespace Heroku\AppLink;

/**
 * The status of an AppLink authorization.
 */
enum AuthorizationStatus: string
{
    /** The authorization is being authorized. */
    case Authorizing = 'authorizing';

    /** The authorization is usable. */
    case Authorized = 'authorized';

    /** The authorization is being disconnected. */
    case Disconnecting = 'disconnecting';

    /** The authorization was disconnected. */
    case Disconnected = 'disconnected';

    /** Authorizing failed with an unexpected error. */
    case Failed = 'failed';
}
