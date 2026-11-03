using System.Diagnostics;

namespace Heroku.AppLink.Tests;

public sealed class ServerFixture : IDisposable
{
    public TlsServer Server { get; } = new();

    public void Dispose() => Server.Dispose();
}

public sealed class GetAuthorizationTests : IClassFixture<ServerFixture>, IDisposable
{
    private const int Mib = 1024 * 1024;
    private static readonly string ValidBody = Vectors.Load("authorization-response.json")[0].String("body")!;
    private static readonly TimeSpan Timeout = TimeSpan.FromMilliseconds(500);

    private readonly TlsServer _server;
    private readonly HttpMessageInvoker _client;
    private readonly HttpSettings _settings;
    private readonly Dictionary<string, string> _env;

    public GetAuthorizationTests(ServerFixture fixture)
    {
        _server = fixture.Server;
        _client = _server.CreateClient();
        _settings = new HttpSettings(
            _client,
            Timeout,
            [TimeSpan.FromMilliseconds(1), TimeSpan.FromMilliseconds(2), TimeSpan.FromMilliseconds(3)]
        );
        _env = new()
        {
            ["HEROKU_APPLINK_API_URL"] = _server.Url + "/addons/abc/",
            ["HEROKU_APPLINK_TOKEN"] = "addon-token",
            ["HEROKU_APP_ID"] = "app-uuid",
        };
    }

    public void Dispose() => _client.Dispose();

    private Task<AuthorizationAppLinkContext> Get(
        string developerName = "x",
        string? attachment = null,
        HttpSettings? settings = null,
        Dictionary<string, string>? env = null
    ) =>
        HerokuAppLink.GetAuthorizationAsync(
            env ?? _env,
            settings ?? _settings,
            developerName,
            attachment,
            TestContext.Current.CancellationToken
        );

    private async Task AssertCanceledAfter(TimeSpan delay, HttpSettings settings)
    {
        using var cancellation = CancellationTokenSource.CreateLinkedTokenSource(TestContext.Current.CancellationToken);
        cancellation.CancelAfter(delay);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            HerokuAppLink.GetAuthorizationAsync(_env, settings, "x", null, cancellation.Token));
    }

    [Fact]
    public async Task SendsTheDocumentedRequest()
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody));
        var context = await Get("my org/x");
        Assert.Equal("00DJS0000000123!AQ0AQ.token", context.Credentials.AccessToken);
        var request = Assert.Single(_server.Requests);
        Assert.Equal("GET", request.Method);
        Assert.Equal("/addons/abc/authorizations/my%20org%2Fx", request.Target);
        Assert.Equal("Bearer addon-token", request.Headers["Authorization"]);
        Assert.Equal("app-uuid", request.Headers["X-App-UUID"]);
        Assert.Equal("application/json", request.Headers["Accept"]);
        Assert.Equal(HerokuAppLink.UserAgent, request.Headers["User-Agent"]);
    }

    [Theory]
    [InlineData(".", "/addons/abc/authorizations/.")]
    [InlineData("..", "/addons/abc/authorizations/..")]
    [InlineData("A~b", "/addons/abc/authorizations/A~b")]
    public async Task SendsThePathAsIs(string developerName, string target)
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody));
        await Get(developerName);
        Assert.Equal(target, Assert.Single(_server.Requests).Target);
    }

    [Fact]
    public async Task ConfigurationErrorsSendNoRequest()
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody));
        await Assert.ThrowsAsync<ConfigurationException>(() => Get(""));
        await Assert.ThrowsAsync<ConfigurationException>(() => Get(attachment: ""));
        await Assert.ThrowsAsync<ConfigurationException>(() => Get(env: new(_env) { ["HEROKU_APP_ID"] = "" }));
        await Assert.ThrowsAsync<ConfigurationException>(() =>
            Get(env: new(_env) { ["HEROKU_APPLINK_API_URL"] = "https://exa mple.tld" }));
        Assert.Empty(_server.Requests);
    }

    [Theory]
    [InlineData("HEROKU_APPLINK_TOKEN", "secret\ntoken")]
    [InlineData("HEROKU_APPLINK_TOKEN", "secret-tok\u20acn")]
    [InlineData("HEROKU_APP_ID", "app-uuid\r\nX-Injected: secret")]
    [InlineData("HEROKU_APPLINK_API_URL", "https:///addons/abc")]
    public async Task ConfigVarsTheRequestCantBeBuiltFrom(string name, string value)
    {
        _server.Respond(TlsServer.RespondWith(400, ""));
        var e = await Assert.ThrowsAnyAsync<AppLinkException>(() =>
            Get(env: new(_env) { [name] = value }, settings: _settings with { RetryDelays = [] }));
        Assert.DoesNotContain("secret", e.ToString());
    }

    [Fact]
    public async Task ReportsStatusAndBody()
    {
        _server.Respond(TlsServer.RespondWith(404, """{"message":"not found"}"""));
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal(404, e.Status);
        Assert.Equal("""{"message":"not found"}""", e.Body);
        Assert.Single(_server.Requests);
    }

    [Fact]
    public async Task ReplacesInvalidUtf8InErrorBodies()
    {
        _server.Respond(TlsServer.RespondWith(400, [(byte)'a', 0xff, (byte)'b']));
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal("a�b", e.Body);
    }

    [Fact]
    public async Task DoesntFollowRedirects()
    {
        _server.Respond(TlsServer.RespondWith(302, "", new Dictionary<string, string> { ["Location"] = "/elsewhere" }));
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal(302, e.Status);
        Assert.Single(_server.Requests);
    }

    [Theory]
    [InlineData(429)]
    [InlineData(500)]
    [InlineData(503)]
    public async Task Retries(int status)
    {
        _server.Respond(TlsServer.RespondWith(status, "busy"), TlsServer.RespondWith(200, ValidBody));
        await Get();
        Assert.Equal(2, _server.Requests.Count);
    }

    [Fact]
    public async Task ReportsTheLastErrorAfterThreeRetries()
    {
        _server.Respond(
            TlsServer.RespondWith(500, "first"),
            TlsServer.RespondWith(500, "second"),
            TlsServer.RespondWith(500, "third"),
            TlsServer.RespondWith(503, "last")
        );
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal(503, e.Status);
        Assert.Equal("last", e.Body);
        Assert.Equal(4, _server.Requests.Count);
    }

    [Fact]
    public async Task DoesntRetryOtherResponses()
    {
        _server.Respond(TlsServer.RespondWith(400, "bad"), TlsServer.RespondWith(200, ValidBody));
        await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Single(_server.Requests);
    }

    [Fact]
    public async Task DoesntRetryUnusableAuthorizations()
    {
        _server.Respond(TlsServer.RespondWith(200, """{"status":"failed"}"""), TlsServer.RespondWith(200, ValidBody));
        var e = await Assert.ThrowsAsync<UnusableAuthorizationException>(() => Get());
        Assert.Equal(AuthorizationStatus.Failed, e.Status);
        Assert.Single(_server.Requests);
    }

    [Fact]
    public async Task RetriesAfterTimeouts()
    {
        _server.Respond((_, cancellationToken) => Task.Delay(TimeSpan.FromSeconds(1), cancellationToken));
        await Assert.ThrowsAsync<TransportException>(() => Get());
        Assert.Equal(4, _server.Requests.Count);
    }

    [Fact]
    public async Task TimesOutWhileReadingASlowBody()
    {
        _server.Respond(
            async (stream, cancellationToken) =>
            {
                await stream.WriteAsync("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n"u8.ToArray(), cancellationToken);
                for (var i = 0; i < 10; i++)
                {
                    await stream.WriteAsync(" "u8.ToArray(), cancellationToken);
                    await stream.FlushAsync(cancellationToken);
                    await Task.Delay(100, cancellationToken);
                }
            }
        );
        await Assert.ThrowsAsync<TransportException>(() => Get(settings: _settings with { RetryDelays = [] }));
    }

    [Fact]
    public async Task ConnectionFailures()
    {
        await Assert.ThrowsAsync<TransportException>(() =>
            Get(env: new(_env) { ["HEROKU_APPLINK_API_URL"] = "https://127.0.0.1:1" }));
    }

    [Fact]
    public async Task UntrustedCertificates()
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody));
        using var untrusting = _server.CreateClient(trusted: false);
        await Assert.ThrowsAsync<TransportException>(() =>
            Get(settings: new HttpSettings(untrusting, Timeout, [])));
    }

    [Fact]
    public async Task CallerCancellationIsNotATransportError()
    {
        _server.Respond((_, cancellationToken) => Task.Delay(TimeSpan.FromSeconds(1), cancellationToken));
        await AssertCanceledAfter(TimeSpan.FromMilliseconds(100), _settings);
    }

    [Fact]
    public async Task CallerCancellationStopsARetryDelay()
    {
        _server.Respond(TlsServer.RespondWith(500, "busy"));
        var stopwatch = Stopwatch.StartNew();
        await AssertCanceledAfter(TimeSpan.FromMilliseconds(100), _settings with { RetryDelays = [TimeSpan.FromSeconds(10)] });
        Assert.True(stopwatch.Elapsed < TimeSpan.FromSeconds(5));
        Assert.Single(_server.Requests);
    }

    [Fact]
    public async Task PublicOverloadReadsTheEnvironment()
    {
        var id = Guid.NewGuid().ToString("N");
        var apiUrlVar = $"APPLINK_TEST_{id}_API_URL";
        var url = $"https://{id}.example.tld";
        Environment.SetEnvironmentVariable(apiUrlVar, url);
        try
        {
            var e = await Assert.ThrowsAsync<ConfigurationException>(() =>
                HerokuAppLink.GetAuthorizationAsync("x", url, TestContext.Current.CancellationToken));
            Assert.Equal($"The config var APPLINK_TEST_{id}_TOKEN is missing or empty.", e.Message);
        }
        finally
        {
            Environment.SetEnvironmentVariable(apiUrlVar, null);
        }
    }

    [Fact]
    public async Task Rejects2xxBodiesLargerThan1Mib()
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody + new string(' ', Mib)));
        await Assert.ThrowsAsync<UnexpectedResponseException>(() => Get());
    }

    [Fact]
    public async Task Accepts2xxBodiesOfExactly1Mib()
    {
        _server.Respond(TlsServer.RespondWith(200, ValidBody + new string(' ', Mib - ValidBody.Length)));
        await Get();
    }

    [Fact]
    public async Task AcceptsBodiesWithoutContentLength()
    {
        _server.Respond(TlsServer.RespondWithoutContentLength(200, ValidBody + new string(' ', Mib - ValidBody.Length)));
        await Get();
    }

    [Fact]
    public async Task Rejects2xxBodiesLargerThan1MibWithoutContentLength()
    {
        _server.Respond(TlsServer.RespondWithoutContentLength(200, ValidBody + new string(' ', Mib)));
        await Assert.ThrowsAsync<UnexpectedResponseException>(() => Get());
    }

    [Fact]
    public async Task TruncatesErrorBodiesWithoutContentLengthTo1Mib()
    {
        _server.Respond(TlsServer.RespondWithoutContentLength(400, new string('x', 2 * Mib)));
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal(new string('x', Mib), e.Body);
    }

    [Fact]
    public async Task TruncatesErrorBodiesTo1Mib()
    {
        _server.Respond(TlsServer.RespondWith(400, new string('x', 2 * Mib)));
        var e = await Assert.ThrowsAsync<HttpException>(() => Get());
        Assert.Equal(Mib, e.Body.Length);
    }

    [Fact]
    public async Task UnexpectedResponsesDontLeakTheBody()
    {
        _server.Respond(
            TlsServer.RespondWith(200, """{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}""")
        );
        var e = await Assert.ThrowsAsync<UnexpectedResponseException>(() => Get());
        Assert.DoesNotContain("secret-token", e.Message, StringComparison.Ordinal);
    }
}
