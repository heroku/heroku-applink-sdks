<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use Heroku\AppLink\AppLink;
use Heroku\AppLink\InvalidInboundDataException;
use Heroku\AppLink\Tests\Support\Traces;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

final class InvocationContextTest extends TestCase
{
    /**
     * @return array<string, array{array<string, mixed>}>
     */
    public static function vectors(): array
    {
        return Vectors::load('invocation-context.json');
    }

    /**
     * @param array<string, mixed> $vector
     */
    #[DataProvider('vectors')]
    public function testVectors(array $vector): void
    {
        $getHeader = fn(string $name): ?string => $name === 'x-client-context'
            ? Vectors::optionalString($vector['header'])
            : null;

        if (!isset($vector['expected'])) {
            $this->expectException(InvalidInboundDataException::class);
            AppLink::parseInvocationContext($getHeader);
            return;
        }

        $expected = Vectors::object($vector['expected']);
        $org = Vectors::object($expected['org']);
        $user = Vectors::object($expected['user']);
        try {
            $context = AppLink::parseInvocationContext($getHeader);
        } catch (InvalidInboundDataException $e) {
            if (!isset($vector['error'])) {
                throw $e;
            }
            self::assertInstanceOf(InvalidInboundDataException::class, $e);
            return;
        }
        self::assertSame($expected['requestId'], $context->requestId);
        self::assertSame($expected['namespace'], $context->namespace);
        self::assertSame($org['id'], $context->org->id);
        self::assertSame($org['domainUrl'], $context->org->domainUrl);
        self::assertSame($org['apiVersion'], $context->org->apiVersion);
        self::assertNull($context->org->type);
        self::assertSame($user['id'], $context->user->id);
        self::assertSame($user['username'], $context->user->username);
        if ($expected['credentials'] === null) {
            self::assertNull($context->credentials);
        } else {
            self::assertSame(Vectors::object($expected['credentials'])['accessToken'], $context->credentials?->accessToken);
            self::assertNull($context->credentials?->expiration);
        }
    }

    public function testAsksForTheHeaderInLowerCase(): void
    {
        $names = [];
        try {
            AppLink::parseInvocationContext(function (string $name) use (&$names): ?string {
                $names[] = $name;
                return null;
            });
            self::fail('Expected an exception.');
        } catch (InvalidInboundDataException) {
        }
        self::assertSame(['x-client-context'], $names);
    }

    public function testErrorsDontLeakTheHeader(): void
    {
        $header = base64_encode('{"accessToken":"secret-token","version":"1.0"');
        try {
            AppLink::parseInvocationContext(fn(string $name): string => $header);
            self::fail('Expected an exception.');
        } catch (InvalidInboundDataException $e) {
            self::assertStringNotContainsString('secret-token', $e->getMessage());
            self::assertNull($e->getPrevious());
        }
    }

    public function testErrorTracesDontLeakTheHeader(): void
    {
        $header = base64_encode('{"accessToken":"secret-token","userContext":{},"version":"1.0"}');
        $args = Traces::sdkArgs(fn() => AppLink::parseInvocationContext(fn(string $name): string => $header));
        self::assertStringNotContainsString('secret-token', $args);
    }
}
