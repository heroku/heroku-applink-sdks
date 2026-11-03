<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use Heroku\AppLink\ConfigurationException;
use Heroku\AppLink\Internal\AttachmentResolver;
use Heroku\AppLink\Internal\AuthorizationResponseParser;
use Heroku\AppLink\OrgType;
use Heroku\AppLink\Tests\Support\Traces;
use Heroku\AppLink\UnexpectedResponseException;
use Heroku\AppLink\UnusableAuthorizationException;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

final class AuthorizationTest extends TestCase
{
    /**
     * @return array<string, array{array<string, mixed>}>
     */
    public static function responseVectors(): array
    {
        return Vectors::load('authorization-response.json');
    }

    /**
     * @return array<string, array{array<string, mixed>}>
     */
    public static function attachmentVectors(): array
    {
        return Vectors::load('attachment-resolution.json');
    }

    /**
     * @return array<string, array{array<string, mixed>}>
     */
    public static function pathVectors(): array
    {
        return Vectors::load('request-path.json', 'developerName');
    }

    /**
     * @param array<string, mixed> $vector
     */
    #[DataProvider('responseVectors')]
    public function testResponseVectors(array $vector): void
    {
        $body = Vectors::string($vector['body']);
        $error = $vector['error'] ?? null;
        $expectsError = !isset($vector['expected']) || $error !== null;
        try {
            $context = AuthorizationResponseParser::parse($body);
        } catch (UnusableAuthorizationException $e) {
            if (!$expectsError || $error !== 'unusable_authorization') {
                throw $e;
            }
            self::assertSame($vector['status'], $e->status->value);
            return;
        } catch (UnexpectedResponseException $e) {
            if (!$expectsError || $error === 'unusable_authorization') {
                throw $e;
            }
            self::assertNull($e->getPrevious());
            return;
        }
        if (!isset($vector['expected'])) {
            self::fail('Expected an exception.');
        }

        $expected = Vectors::object($vector['expected']);
        $expectedMetadata = Vectors::object($expected['authorizationMetadata']);
        $org = Vectors::object($expected['org']);
        $user = Vectors::object($expected['user']);
        $credentials = Vectors::object($expected['credentials']);
        $metadata = $context->authorizationMetadata;
        self::assertSame($expectedMetadata['id'], $metadata->id);
        self::assertSame($expectedMetadata['developerName'], $metadata->developerName);
        self::assertSame(Vectors::instant($expectedMetadata['createdAt']), Vectors::format($metadata->createdAt));
        self::assertSame('UTC', $metadata->createdAt->getTimezone()->getName());
        self::assertSame($expectedMetadata['createdBy'], $metadata->createdBy);
        self::assertSame($expectedMetadata['createdViaApp'], $metadata->createdViaApp);
        self::assertSame(Vectors::instant($expectedMetadata['lastModifiedAt']), Vectors::format($metadata->lastModifiedAt));
        self::assertSame('UTC', $metadata->lastModifiedAt->getTimezone()->getName());
        self::assertSame($expectedMetadata['lastModifiedBy'], $metadata->lastModifiedBy);
        self::assertSame($expectedMetadata['redirectUri'], $metadata->redirectUri);
        self::assertSame($org['id'], $context->org->id);
        self::assertSame($org['domainUrl'], $context->org->domainUrl);
        self::assertSame($org['apiVersion'], $context->org->apiVersion);
        self::assertSame(OrgType::from(Vectors::string($org['type'])), $context->org->type);
        self::assertSame($user['id'], $context->user->id);
        self::assertSame($user['username'], $context->user->username);
        self::assertSame($credentials['accessToken'], $context->credentials->accessToken);
        self::assertSame(Vectors::instant($credentials['expiration']), Vectors::format($context->credentials->expiration));
        if ($context->credentials->expiration !== null) {
            self::assertSame('UTC', $context->credentials->expiration->getTimezone()->getName());
        }
    }

    public function testResponseErrorsDontLeakTheBody(): void
    {
        try {
            AuthorizationResponseParser::parse('{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}');
            self::fail('Expected an exception.');
        } catch (UnexpectedResponseException $e) {
            self::assertStringNotContainsString('secret-token', $e->getMessage());
        }
    }

    /**
     * @return array<string, array{string}>
     */
    public static function invalidResponsesWithToken(): array
    {
        $response = [
            'status' => 'authorized',
            'created_at' => '2026-10-13T09:15:42Z',
            'last_modified_at' => '2026-10-13T09:15:42Z',
            'org' => [
                'id' => '00DJS0000000123ABC',
                'developer_name' => 'example',
                'instance_url' => 'https://example.my.salesforce.com',
                'type' => 'SalesforceOrg',
                'api_version' => '62.0',
                'user_auth' => ['username' => 'user@example.tld', 'user_id' => '005JS000000H123', 'access_token' => 'secret-token'],
            ],
        ];
        $invalidExpiration = $response;
        $invalidExpiration['id'] = 'e27e9be0-6dc4-430f-974d-584f5ff8e9e6';
        $invalidExpiration['created_by'] = 'user@example.tld';
        $invalidExpiration['org']['user_auth']['expiration'] = 'soon';
        return [
            'missing id' => [json_encode($response, JSON_THROW_ON_ERROR)],
            'invalid expiration' => [json_encode($invalidExpiration, JSON_THROW_ON_ERROR)],
        ];
    }

    #[DataProvider('invalidResponsesWithToken')]
    public function testResponseErrorTracesDontLeakTheBody(string $body): void
    {
        self::assertStringNotContainsString('secret-token', Traces::sdkArgs(fn() => AuthorizationResponseParser::parse($body)));
    }

    /**
     * @param array<string, mixed> $vector
     */
    #[DataProvider('attachmentVectors')]
    public function testAttachmentVectors(array $vector): void
    {
        /** @var array<string, string> $env */
        $env = Vectors::object($vector['env']);
        $attachment = Vectors::optionalString($vector['attachment']);
        if (!isset($vector['expected'])) {
            $this->expectException(ConfigurationException::class);
            AttachmentResolver::resolve($env, $attachment);
            return;
        }
        $expected = Vectors::object($vector['expected']);
        $resolved = AttachmentResolver::resolve($env, $attachment);
        self::assertSame($expected['apiUrl'], $resolved->apiUrl);
        self::assertSame($expected['token'], $resolved->token);
        self::assertSame($expected['appId'], $resolved->appId);
    }

    /**
     * @param array<string, mixed> $vector
     */
    #[DataProvider('pathVectors')]
    public function testPathVectors(array $vector): void
    {
        self::assertSame($vector['path'], AuthorizationResponseParser::path(Vectors::string($vector['developerName'])));
    }
}
