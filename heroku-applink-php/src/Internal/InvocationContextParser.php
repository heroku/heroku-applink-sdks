<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

use Heroku\AppLink\Credentials;
use Heroku\AppLink\InvalidInboundDataException;
use Heroku\AppLink\InvocationAppLinkContext;
use Heroku\AppLink\Org;
use Heroku\AppLink\User;
use stdClass;

/**
 * @internal
 */
final class InvocationContextParser
{
    public const HEADER_NAME = 'x-client-context';

    /**
     * @param callable(string): ?string $getHeader
     */
    public static function parse(callable $getHeader): InvocationAppLinkContext
    {
        $header = $getHeader(self::HEADER_NAME);
        if (!is_string($header) || $header === '') {
            throw new InvalidInboundDataException('The ' . self::HEADER_NAME . ' header is missing.');
        }

        $decoded = base64_decode($header, true);
        if ($decoded === false) {
            throw new InvalidInboundDataException('The ' . self::HEADER_NAME . ' header is not valid base64.');
        }

        $data = Json::decodeObject($decoded);
        if ($data === null) {
            throw new InvalidInboundDataException(
                'The ' . self::HEADER_NAME . " header doesn't contain a UTF-8 JSON object.",
            );
        }
        if (Json::get($data, 'version') !== '1.0') {
            throw new InvalidInboundDataException(
                'The ' . self::HEADER_NAME . " header doesn't have the supported version 1.0.",
            );
        }

        $userContext = Json::get($data, 'userContext');
        if (!$userContext instanceof stdClass) {
            throw self::invalidField('userContext');
        }

        $accessToken = Json::get($data, 'accessToken');
        if ($accessToken !== null && Json::nonEmptyString($accessToken) === null) {
            throw self::invalidField('accessToken');
        }
        $namespace = Json::get($data, 'namespace');
        if ($namespace !== null && !is_string($namespace)) {
            throw self::invalidField('namespace');
        }

        return new InvocationAppLinkContext(
            org: new Org(
                id: self::requiredString($data, 'orgId'),
                domainUrl: self::requiredString($data, 'orgDomainUrl'),
                apiVersion: self::requiredString($data, 'apiVersion'),
            ),
            user: new User(
                id: self::requiredString($userContext, 'userId', 'userContext.'),
                username: self::requiredString($userContext, 'username', 'userContext.'),
            ),
            requestId: self::requiredString($data, 'requestId'),
            namespace: $namespace === '' ? null : $namespace,
            credentials: is_string($accessToken) ? new Credentials($accessToken) : null,
        );
    }

    private static function requiredString(#[\SensitiveParameter] stdClass $object, string $name, string $prefix = ''): string
    {
        return Json::nonEmptyString(Json::get($object, $name)) ?? throw self::invalidField($prefix . $name);
    }

    private static function invalidField(string $field): InvalidInboundDataException
    {
        return new InvalidInboundDataException('The ' . self::HEADER_NAME . " header has no valid \"$field\" field.");
    }
}
