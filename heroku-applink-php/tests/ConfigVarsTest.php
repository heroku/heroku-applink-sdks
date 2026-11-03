<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use Heroku\AppLink\Internal\ConfigVars;
use PHPUnit\Framework\TestCase;

final class ConfigVarsTest extends TestCase
{
    public function testReadsAllSources(): void
    {
        self::assertSame(
            ['FROM_PROCESS' => 'a', 'FROM_ENV' => 'b', 'FROM_SERVER' => 'c'],
            ConfigVars::read(['FROM_PROCESS' => 'a'], ['FROM_ENV' => 'b'], ['FROM_SERVER' => 'c']),
        );
    }

    public function testPrefersTheProcessEnvironmentThenEnv(): void
    {
        self::assertSame(
            ['X' => 'process', 'Y' => 'env'],
            ConfigVars::read(['X' => 'process'], ['X' => 'env', 'Y' => 'env'], ['X' => 'server', 'Y' => 'server']),
        );
    }

    public function testIgnoresRequestHeadersAndNonStringValues(): void
    {
        self::assertSame(
            ['SCRIPT_NAME' => '/index.php'],
            ConfigVars::read([], [], [
                'HTTP_X_APPLINK_API_URL' => 'https://attacker.tld',
                'argv' => ['index.php'],
                'REQUEST_TIME' => 1,
                'SCRIPT_NAME' => '/index.php',
            ]),
        );
    }
}
