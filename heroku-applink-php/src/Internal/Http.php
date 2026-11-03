<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

use Heroku\AppLink\TransportException;

/**
 * @internal
 */
final class Http
{
    public const MAX_BODY_BYTES = 1024 * 1024;

    /**
     * @param non-empty-string $url
     * @param list<string> $headers
     */
    public static function getWithRetries(
        HttpSettings $settings,
        string $url,
        #[\SensitiveParameter]
        array $headers,
    ): HttpResponse {
        for ($attempt = 0; ; $attempt++) {
            $lastAttempt = $attempt >= count($settings->retryDelays);
            try {
                $response = self::get($settings, $url, $headers);
                $retryable = $response->status === 429 || ($response->status >= 500 && $response->status <= 599);
                if ($lastAttempt || !$retryable) {
                    return $response;
                }
            } catch (TransportException $e) {
                if ($lastAttempt) {
                    throw $e;
                }
            }
            usleep((int) ($settings->retryDelays[$attempt] * 1_000_000));
        }
    }

    /**
     * @param non-empty-string $url
     * @param list<string> $headers
     */
    private static function get(
        HttpSettings $settings,
        string $url,
        #[\SensitiveParameter]
        array $headers,
    ): HttpResponse {
        $body = '';
        $bodyTooLarge = false;
        $handle = curl_init();
        $timeoutMs = (int) ($settings->timeout * 1000);
        $options = [
            CURLOPT_URL => $url,
            CURLOPT_HTTPGET => true,
            CURLOPT_HTTPHEADER => $headers,
            CURLOPT_FOLLOWLOCATION => false,
            CURLOPT_PATH_AS_IS => true,
            CURLOPT_PROTOCOLS_STR => 'https',
            CURLOPT_TIMEOUT_MS => $timeoutMs,
            CURLOPT_CONNECTTIMEOUT_MS => $timeoutMs,
            CURLOPT_NOSIGNAL => true,
            CURLOPT_WRITEFUNCTION => static function ($handle, string $data) use (&$body, &$bodyTooLarge): int {
                $body .= substr($data, 0, self::MAX_BODY_BYTES + 1 - strlen($body));
                if (strlen($body) > self::MAX_BODY_BYTES) {
                    $bodyTooLarge = true;
                    return -1;
                }
                return strlen($data);
            },
        ];
        if ($settings->caFile !== null) {
            $options[CURLOPT_CAINFO] = $settings->caFile;
        }
        curl_setopt_array($handle, $options);

        $succeeded = curl_exec($handle);
        $status = curl_getinfo($handle, CURLINFO_RESPONSE_CODE);
        if ($succeeded === false && !$bodyTooLarge) {
            $message = curl_errno($handle) === CURLE_OPERATION_TIMEDOUT
                ? 'The request to the AppLink API timed out.'
                : 'The request to the AppLink API failed: ' . curl_error($handle);
            throw new TransportException($message);
        }
        return new HttpResponse($status, substr($body, 0, self::MAX_BODY_BYTES), $bodyTooLarge);
    }

    public static function decodeErrorBody(string $body): string
    {
        $previous = mb_substitute_character();
        mb_substitute_character(0xFFFD);
        try {
            return mb_scrub($body, 'UTF-8');
        } finally {
            mb_substitute_character($previous);
        }
    }
}
