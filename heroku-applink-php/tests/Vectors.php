<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use DateTimeImmutable;
use DateTimeZone;

final class Vectors
{
    /**
     * @return array<string, array{array<string, mixed>}>
     */
    public static function load(string $name, string $key = 'name'): array
    {
        $contents = (string) file_get_contents(__DIR__ . '/../../test-vectors/' . $name);
        /** @var array{cases: list<array<string, mixed>>} $vectors */
        $vectors = json_decode($contents, true, flags: JSON_THROW_ON_ERROR);
        $cases = [];
        foreach ($vectors['cases'] as $case) {
            $cases[self::string($case[$key])] = [$case];
        }
        return $cases;
    }

    public static function string(mixed $value): string
    {
        return is_string($value) ? $value : throw new \UnexpectedValueException('Expected a string.');
    }

    public static function optionalString(mixed $value): ?string
    {
        return $value === null ? null : self::string($value);
    }

    /**
     * @return array<string, mixed>
     */
    public static function object(mixed $value): array
    {
        if (!is_array($value)) {
            throw new \UnexpectedValueException('Expected an object.');
        }
        /** @var array<string, mixed> $value */
        return $value;
    }

    public static function instant(mixed $value): ?string
    {
        return $value === null ? null : self::format((new DateTimeImmutable(self::string($value)))->setTimezone(new DateTimeZone('UTC')));
    }

    public static function format(?DateTimeImmutable $value): ?string
    {
        return $value?->format('Y-m-d\TH:i:s.u e');
    }
}
