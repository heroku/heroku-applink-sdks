<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests;

use Heroku\AppLink\AppLink;
use Heroku\AppLink\AppLinkException;
use Heroku\AppLink\ConfigurationException;
use Heroku\AppLink\HttpException;
use Heroku\AppLink\Internal\HttpSettings;
use Heroku\AppLink\Tests\Support\TlsServer;
use Heroku\AppLink\TransportException;
use Heroku\AppLink\UnexpectedResponseException;
use Heroku\AppLink\UnusableAuthorizationException;
use PHPUnit\Framework\Attributes\TestWith;
use PHPUnit\Framework\TestCase;

final class GetAuthorizationTest extends TestCase
{
    private const MIB = 1024 * 1024;

    private static ?TlsServer $server = null;
    private static string $validBody;
    private HttpSettings $settings;
    /** @var array<string, string> */
    private array $env;

    public static function setUpBeforeClass(): void
    {
        self::$server = new TlsServer();
        self::$validBody = Vectors::string(Vectors::load('authorization-response.json')['all fields'][0]['body']);
    }

    public static function tearDownAfterClass(): void
    {
        self::$server?->stop();
        self::$server = null;
    }

    protected function setUp(): void
    {
        $this->settings = new HttpSettings(0.5, [0.001, 0.002, 0.003], self::server()->caFile);
        $this->env = [
            'HEROKU_APPLINK_API_URL' => self::server()->url . '/addons/abc/',
            'HEROKU_APPLINK_TOKEN' => 'addon-token',
            'HEROKU_APP_ID' => 'app-uuid',
        ];
    }

    public function testSendsTheDocumentedRequest(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody));
        $context = AppLink::getAuthorizationWith($this->env, $this->settings, 'my org/x');
        self::assertSame('00DJS0000000123!AQ0AQ.token', $context->credentials->accessToken);
        $requests = self::server()->requests();
        self::assertCount(1, $requests);
        self::assertSame('GET', $requests[0]['method']);
        self::assertSame('/addons/abc/authorizations/my%20org%2Fx', $requests[0]['path']);
        self::assertSame('Bearer addon-token', $requests[0]['headers']['authorization']);
        self::assertSame('app-uuid', $requests[0]['headers']['x-app-uuid']);
        self::assertSame('application/json', $requests[0]['headers']['accept']);
        self::assertSame(AppLink::USER_AGENT, $requests[0]['headers']['user-agent']);
    }

    #[TestWith(['.'])]
    #[TestWith(['..'])]
    public function testSendsDotSegmentsAsIs(string $developerName): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody));
        AppLink::getAuthorizationWith($this->env, $this->settings, $developerName);
        self::assertSame("/addons/abc/authorizations/$developerName", self::server()->requests()[0]['path']);
    }

    public function testConfigurationErrorsSendNoRequest(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody));
        foreach (
            [
                fn() => AppLink::getAuthorizationWith($this->env, $this->settings, ''),
                fn() => AppLink::getAuthorizationWith($this->env, $this->settings, 'x', ''),
                fn() => AppLink::getAuthorizationWith(['HEROKU_APP_ID' => ''] + $this->env, $this->settings, 'x'),
            ] as $call
        ) {
            try {
                $call();
                self::fail('Expected an exception.');
            } catch (ConfigurationException) {
            }
        }
        self::assertSame([], self::server()->requests());
    }

    #[TestWith(['HEROKU_APPLINK_TOKEN', "secret\ntoken"])]
    #[TestWith(['HEROKU_APP_ID', "app-uuid\r\nX-Injected: secret"])]
    #[TestWith(['HEROKU_APPLINK_API_URL', 'https://127.0.0.1:1/a b'])]
    #[TestWith(['HEROKU_APPLINK_API_URL', 'https:///addons/abc'])]
    public function testConfigVarsTheRequestCantBeBuiltFrom(string $name, string $value): void
    {
        self::server()->respond(TlsServer::respondWith(400, ''));
        try {
            AppLink::getAuthorizationWith([$name => $value] + $this->env, new HttpSettings(0.5, [], self::server()->caFile), 'x');
            self::fail('Expected an exception.');
        } catch (AppLinkException $e) {
            self::assertStringNotContainsString('secret', $e->getMessage());
            self::assertNull($e->getPrevious());
        }
    }

    public function testReportsStatusAndBody(): void
    {
        self::server()->respond(TlsServer::respondWith(404, '{"message":"not found"}'));
        $e = $this->expectHttpException();
        self::assertSame(404, $e->status);
        self::assertSame('{"message":"not found"}', $e->body);
        self::assertCount(1, self::server()->requests());
    }

    public function testReplacesInvalidUtf8InErrorBodies(): void
    {
        self::server()->respond(TlsServer::respondWith(400, "a\xffb"));
        self::assertSame("a\u{FFFD}b", $this->expectHttpException()->body);
    }

    public function testDoesntFollowRedirects(): void
    {
        self::server()->respond(TlsServer::respondWith(302, headers: ['Location' => '/elsewhere']));
        self::assertSame(302, $this->expectHttpException()->status);
        self::assertCount(1, self::server()->requests());
    }

    #[TestWith([429])]
    #[TestWith([500])]
    #[TestWith([503])]
    public function testRetries(int $status): void
    {
        self::server()->respond(TlsServer::respondWith($status, 'busy'), TlsServer::respondWith(200, self::$validBody));
        AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
        self::assertCount(2, self::server()->requests());
    }

    public function testReportsTheLastErrorAfterThreeRetries(): void
    {
        self::server()->respond(
            TlsServer::respondWith(500, 'first'),
            TlsServer::respondWith(500, 'second'),
            TlsServer::respondWith(500, 'third'),
            TlsServer::respondWith(503, 'last'),
        );
        $e = $this->expectHttpException();
        self::assertSame(503, $e->status);
        self::assertSame('last', $e->body);
        self::assertCount(4, self::server()->requests());
    }

    public function testDoesntRetryOtherResponses(): void
    {
        self::server()->respond(TlsServer::respondWith(400, 'bad'), TlsServer::respondWith(200, self::$validBody));
        $this->expectHttpException();
        self::assertCount(1, self::server()->requests());
    }

    public function testDoesntRetryUnusableAuthorizations(): void
    {
        self::server()->respond(TlsServer::respondWith(200, '{"status":"failed"}'), TlsServer::respondWith(200, self::$validBody));
        try {
            AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
            self::fail('Expected an exception.');
        } catch (UnusableAuthorizationException) {
        }
        self::assertCount(1, self::server()->requests());
    }

    public function testRetriesAfterTimeouts(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody, delay: 1.0));
        $this->expectTransportException($this->env, $this->settings);
        self::assertCount(4, self::server()->requests());
    }

    public function testTimesOutWhileReadingASlowBody(): void
    {
        self::server()->respond(TlsServer::respondWith(200, str_repeat(' ', 10), slowBody: true));
        $this->expectTransportException($this->env, new HttpSettings(0.5, [], self::server()->caFile));
    }

    public function testConnectionFailures(): void
    {
        $this->expectTransportException(['HEROKU_APPLINK_API_URL' => 'https://127.0.0.1:1'] + $this->env, $this->settings);
    }

    public function testUntrustedCertificates(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody));
        $this->expectTransportException($this->env, new HttpSettings(0.5, []));
    }

    public function testRejects2xxBodiesLargerThan1Mib(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody . str_repeat(' ', self::MIB)));
        $this->expectException(UnexpectedResponseException::class);
        AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
    }

    public function testAccepts2xxBodiesOfExactly1Mib(): void
    {
        self::server()->respond(TlsServer::respondWith(200, self::$validBody . str_repeat(' ', self::MIB - strlen(self::$validBody))));
        $context = AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
        self::assertSame('example', $context->authorizationMetadata->developerName);
    }

    public function testTruncatesErrorBodiesTo1Mib(): void
    {
        self::server()->respond(TlsServer::respondWith(400, str_repeat('x', 2 * self::MIB)));
        self::assertSame(self::MIB, strlen($this->expectHttpException()->body));
    }

    public function testUnexpectedResponsesDontLeakTheBody(): void
    {
        self::server()->respond(TlsServer::respondWith(200, '{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}'));
        try {
            AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
            self::fail('Expected an exception.');
        } catch (UnexpectedResponseException $e) {
            self::assertStringNotContainsString('secret-token', $e->getMessage());
        }
    }

    private static function server(): TlsServer
    {
        return self::$server ?? throw new \LogicException('The test server is not running.');
    }

    private function expectHttpException(): HttpException
    {
        try {
            AppLink::getAuthorizationWith($this->env, $this->settings, 'x');
        } catch (HttpException $e) {
            return $e;
        }
        self::fail('Expected an HttpException.');
    }

    /**
     * @param array<string, string> $env
     */
    private function expectTransportException(array $env, HttpSettings $settings): void
    {
        try {
            AppLink::getAuthorizationWith($env, $settings, 'x');
            self::fail('Expected a TransportException.');
        } catch (TransportException $e) {
            self::assertStringStartsWith('The request to the AppLink API', $e->getMessage());
        }
    }
}
