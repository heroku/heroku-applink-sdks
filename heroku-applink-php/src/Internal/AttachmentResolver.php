<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

use Heroku\AppLink\ConfigurationException;

/**
 * @internal
 */
final class AttachmentResolver
{
    private const ABSOLUTE_URL = '#^[A-Za-z][A-Za-z0-9+.-]*://#';
    private const HTTPS_URL = '#^https://#i';

    /**
     * @param array<string, string> $env
     */
    public static function resolve(#[\SensitiveParameter] array $env, ?string $attachment): ResolvedAttachment
    {
        if ($attachment === null) {
            $apiUrlVar = 'HEROKU_APPLINK_API_URL';
            $tokenVar = 'HEROKU_APPLINK_TOKEN';
        } elseif ($attachment === '') {
            throw new ConfigurationException('The attachment must not be empty.');
        } elseif (preg_match(self::ABSOLUTE_URL, $attachment) === 1) {
            $wanted = self::normalizeUrl($attachment);
            $matches = [];
            foreach ($env as $name => $value) {
                if (str_ends_with((string) $name, '_API_URL') && self::normalizeUrl($value) === $wanted) {
                    $matches[] = (string) $name;
                }
            }
            sort($matches, SORT_STRING);
            if ($matches === []) {
                throw new ConfigurationException(
                    "No config var ending in _API_URL matches the attachment URL $attachment.",
                );
            }
            if (count($matches) > 1) {
                throw new ConfigurationException(
                    "Several config vars match the attachment URL $attachment: " . implode(', ', $matches) . '.',
                );
            }
            $apiUrlVar = $matches[0];
            $tokenVar = substr($apiUrlVar, 0, -strlen('API_URL')) . 'TOKEN';
        } else {
            $name = strtoupper($attachment);
            if (($env["{$name}_API_URL"] ?? '') !== '' && ($env["{$name}_TOKEN"] ?? '') !== '') {
                $apiUrlVar = "{$name}_API_URL";
                $tokenVar = "{$name}_TOKEN";
            } else {
                $apiUrlVar = "HEROKU_APPLINK_{$name}_API_URL";
                $tokenVar = "HEROKU_APPLINK_{$name}_TOKEN";
            }
        }

        $apiUrl = self::requiredConfigVar($env, $apiUrlVar);
        $token = self::requiredConfigVar($env, $tokenVar);
        $appId = self::requiredConfigVar($env, 'HEROKU_APP_ID');
        if (preg_match(self::HTTPS_URL, $apiUrl) !== 1) {
            throw new ConfigurationException("The config var $apiUrlVar must contain an https URL.");
        }
        return new ResolvedAttachment(self::removeTrailingSlash($apiUrl), $token, $appId);
    }

    /**
     * @param array<string, string> $env
     */
    private static function requiredConfigVar(#[\SensitiveParameter] array $env, string $name): string
    {
        $value = $env[$name] ?? '';
        if ($value === '') {
            throw new ConfigurationException("The config var $name is missing or empty.");
        }
        return $value;
    }

    private static function normalizeUrl(string $url): string
    {
        return self::removeTrailingSlash(strtolower($url));
    }

    private static function removeTrailingSlash(string $value): string
    {
        return str_ends_with($value, '/') ? substr($value, 0, -1) : $value;
    }
}
