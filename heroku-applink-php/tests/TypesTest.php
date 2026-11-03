<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use DateTimeImmutable;
use Heroku\AppLink\AppLink;
use Heroku\AppLink\AppLinkContext;
use Heroku\AppLink\Credentials;
use Heroku\AppLink\InvocationAppLinkContext;
use Heroku\AppLink\Org;
use Heroku\AppLink\User;
use PHPUnit\Framework\TestCase;

final class TypesTest extends TestCase
{
    public function testDumpingRedactsTheAccessToken(): void
    {
        $context = new InvocationAppLinkContext(
            new Org('org', 'https://example.tld', '62.0'),
            new User('user', 'user@example.tld'),
            'request',
            credentials: new Credentials('secret-token', new DateTimeImmutable('2026-10-13T09:15:42Z')),
        );
        $dump = print_r($context, true);
        self::assertStringNotContainsString('secret-token', $dump);
        self::assertStringContainsString('[REDACTED]', $dump);
        ob_start();
        var_dump($context);
        self::assertStringNotContainsString('secret-token', (string) ob_get_clean());
        self::assertInstanceOf(AppLinkContext::class, $context);
    }

    public function testTypesAreImmutable(): void
    {
        $credentials = new Credentials('secret-token');
        $this->expectException(\Error::class);
        // @phpstan-ignore assign.propertyProtectedSet
        $credentials->accessToken = 'other';
    }

    public function testUserAgentContainsTheVersion(): void
    {
        self::assertMatchesRegularExpression('/^[0-9]+\.[0-9]+\.[0-9]+$/', AppLink::VERSION);
        self::assertSame('heroku-applink-php/' . AppLink::VERSION, AppLink::USER_AGENT);
    }
}
