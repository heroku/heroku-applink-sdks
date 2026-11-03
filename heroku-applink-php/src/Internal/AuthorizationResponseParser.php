<?php

declare(strict_types=1);

namespace Heroku\AppLink\Internal;

use DateTimeImmutable;
use DateTimeZone;
use Heroku\AppLink\AuthorizationAppLinkContext;
use Heroku\AppLink\AuthorizationMetadata;
use Heroku\AppLink\AuthorizationStatus;
use Heroku\AppLink\Credentials;
use Heroku\AppLink\Org;
use Heroku\AppLink\OrgType;
use Heroku\AppLink\UnexpectedResponseException;
use Heroku\AppLink\UnusableAuthorizationException;
use Heroku\AppLink\User;
use stdClass;

/**
 * @internal
 */
final class AuthorizationResponseParser
{
    private const MAX_EXPIRATION = 253402300799999;
    private const TIMESTAMP = '#^[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]+)?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})\z#';

    /**
     * @return non-empty-string
     */
    public static function path(string $developerName): string
    {
        return '/authorizations/' . rawurlencode($developerName);
    }

    public static function parse(#[\SensitiveParameter] string $body): AuthorizationAppLinkContext
    {
        $data = Json::decodeObject($body);
        if ($data === null) {
            throw new UnexpectedResponseException('The AppLink API response is not a JSON object.');
        }
        $statusValue = Json::get($data, 'status');
        $status = is_string($statusValue) ? AuthorizationStatus::tryFrom($statusValue) : null;
        if ($status === null) {
            throw self::invalidField('status');
        }
        if ($status !== AuthorizationStatus::Authorized) {
            throw new UnusableAuthorizationException(
                "The authorization has the status {$status->value} instead of authorized.",
                $status,
            );
        }

        $org = self::requiredObject($data, 'org');
        $userAuth = self::requiredObject($org, 'user_auth', 'org.');
        $typeValue = Json::get($org, 'type');
        $orgType = is_string($typeValue) ? OrgType::tryFrom($typeValue) : null;
        if ($orgType === null) {
            throw self::invalidField('org.type');
        }

        return new AuthorizationAppLinkContext(
            org: new Org(
                id: self::requiredString($org, 'id', 'org.'),
                domainUrl: self::requiredString($org, 'instance_url', 'org.'),
                apiVersion: self::requiredString($org, 'api_version', 'org.'),
                type: $orgType,
            ),
            user: new User(
                id: self::requiredString($userAuth, 'user_id', 'org.user_auth.'),
                username: self::requiredString($userAuth, 'username', 'org.user_auth.'),
            ),
            authorizationMetadata: new AuthorizationMetadata(
                id: self::requiredString($data, 'id'),
                developerName: self::requiredString($org, 'developer_name', 'org.'),
                createdAt: self::requiredTimestamp($data, 'created_at'),
                createdBy: self::requiredString($data, 'created_by'),
                createdViaApp: self::optionalString($data, 'created_via_app'),
                lastModifiedAt: self::requiredTimestamp($data, 'last_modified_at'),
                lastModifiedBy: self::optionalString($data, 'last_modified_by'),
                redirectUri: self::optionalString($data, 'redirect_uri'),
            ),
            credentials: new Credentials(
                accessToken: self::requiredString($userAuth, 'access_token', 'org.user_auth.'),
                expiration: self::optionalExpiration($userAuth),
            ),
        );
    }

    private static function requiredObject(#[\SensitiveParameter] stdClass $object, string $name, string $prefix = ''): stdClass
    {
        $value = Json::get($object, $name);
        return $value instanceof stdClass ? $value : throw self::invalidField($prefix . $name);
    }

    private static function requiredString(#[\SensitiveParameter] stdClass $object, string $name, string $prefix = ''): string
    {
        return Json::nonEmptyString(Json::get($object, $name)) ?? throw self::invalidField($prefix . $name);
    }

    private static function optionalString(#[\SensitiveParameter] stdClass $object, string $name): ?string
    {
        $value = Json::get($object, $name);
        if ($value !== null && !is_string($value)) {
            throw self::invalidField($name);
        }
        return $value === '' ? null : $value;
    }

    private static function requiredTimestamp(#[\SensitiveParameter] stdClass $object, string $name): DateTimeImmutable
    {
        $value = self::requiredString($object, $name);
        if (preg_match(self::TIMESTAMP, $value) !== 1) {
            throw self::invalidField($name);
        }
        try {
            $timestamp = new DateTimeImmutable($value);
        } catch (\Exception) {
            throw self::invalidField($name);
        }
        $errors = DateTimeImmutable::getLastErrors();
        if ($errors !== false && ($errors['warning_count'] > 0 || $errors['error_count'] > 0)) {
            throw self::invalidField($name);
        }
        return $timestamp->setTimezone(new DateTimeZone('UTC'));
    }

    private static function optionalExpiration(#[\SensitiveParameter] stdClass $userAuth): ?DateTimeImmutable
    {
        $value = Json::get($userAuth, 'expiration');
        if ($value === null) {
            return null;
        }
        if (!is_int($value) || $value < 0 || $value > self::MAX_EXPIRATION) {
            throw self::invalidField('org.user_auth.expiration');
        }
        if ($value === 0) {
            return null;
        }
        $timestamp = DateTimeImmutable::createFromFormat(
            'U.u',
            sprintf('%d.%06d', intdiv($value, 1000), ($value % 1000) * 1000),
        );
        if ($timestamp === false) {
            throw self::invalidField('org.user_auth.expiration');
        }
        return $timestamp->setTimezone(new DateTimeZone('UTC'));
    }

    private static function invalidField(string $field): UnexpectedResponseException
    {
        return new UnexpectedResponseException("The AppLink API response has no valid \"$field\" field.");
    }
}
