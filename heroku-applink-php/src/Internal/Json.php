<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

use stdClass;

/**
 * @internal
 */
final class Json
{
    public static function decodeObject(#[\SensitiveParameter] string $data): ?stdClass
    {
        try {
            $value = json_decode($data, false, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            return null;
        }
        return $value instanceof stdClass ? $value : null;
    }

    public static function get(stdClass $object, string $name): mixed
    {
        return property_exists($object, $name) ? $object->{$name} : null;
    }

    public static function nonEmptyString(mixed $value): ?string
    {
        return is_string($value) && $value !== '' ? $value : null;
    }
}
