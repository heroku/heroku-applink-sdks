<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

/**
 * @internal
 */
final class ConfigVars
{
    /**
     * @param array<mixed> $processEnv
     * @param array<mixed> $env
     * @param array<mixed> $server
     *
     * @return array<string, string>
     */
    public static function read(
        #[\SensitiveParameter]
        array $processEnv,
        #[\SensitiveParameter]
        array $env,
        #[\SensitiveParameter]
        array $server,
    ): array {
        $configVars = [];
        foreach ([$processEnv, $env, $server] as $source) {
            foreach ($source as $name => $value) {
                // $_SERVER also holds the request headers, which clients control.
                if (!is_string($name) || !is_string($value) || str_starts_with($name, 'HTTP_')) {
                    continue;
                }
                $configVars[$name] ??= $value;
            }
        }
        return $configVars;
    }
}
