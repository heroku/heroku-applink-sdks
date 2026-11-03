using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;

namespace Heroku.AppLink.Tests;

public delegate Task Handler(Stream stream, CancellationToken cancellationToken);

public sealed record Request(string Method, string Target, IReadOnlyDictionary<string, string> Headers);

public sealed class TlsServer : IDisposable
{
    private readonly X509Certificate2 _certificate;
    private readonly TcpListener _listener = new(IPAddress.Loopback, 0);
    private readonly CancellationTokenSource _stop = new();
    private readonly List<Handler> _handlers = [];
    private readonly List<Request> _requests = [];

    public TlsServer()
    {
        using var key = ECDsa.Create(ECCurve.NamedCurves.nistP256);
        var request = new CertificateRequest("CN=localhost", key, HashAlgorithmName.SHA256);
        var san = new SubjectAlternativeNameBuilder();
        san.AddDnsName("localhost");
        san.AddIpAddress(IPAddress.Loopback);
        request.CertificateExtensions.Add(san.Build());
        using var certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddMinutes(-5), DateTimeOffset.UtcNow.AddDays(1));
        _certificate = X509CertificateLoader.LoadPkcs12(certificate.Export(X509ContentType.Pkcs12), null);

        _listener.Start();
        _ = AcceptAsync();
    }

    public string Url => $"https://localhost:{((IPEndPoint)_listener.LocalEndpoint).Port}";

    public IReadOnlyList<Request> Requests
    {
        get
        {
            lock (_requests)
            {
                return [.. _requests];
            }
        }
    }

    public HttpMessageInvoker CreateClient(bool trusted = true) =>
        new(
            new SocketsHttpHandler
            {
                AllowAutoRedirect = false,
                SslOptions = trusted ? new SslClientAuthenticationOptions { RemoteCertificateValidationCallback = Validate } : new(),
            }
        );

    public void Respond(params Handler[] handlers)
    {
        lock (_requests)
        {
            _handlers.Clear();
            _handlers.AddRange(handlers);
            _requests.Clear();
        }
    }

    public static Handler RespondWith(int status, string body, IReadOnlyDictionary<string, string>? headers = null) =>
        RespondWith(status, Encoding.UTF8.GetBytes(body), headers);

    public static Handler RespondWith(int status, byte[] body, IReadOnlyDictionary<string, string>? headers = null) =>
        async (stream, cancellationToken) =>
        {
            var head = new StringBuilder(
                string.Create(CultureInfo.InvariantCulture, $"HTTP/1.1 {status} Status\r\nContent-Length: {body.Length}\r\nConnection: close\r\n")
            );
            foreach (var (name, value) in headers ?? new Dictionary<string, string>())
            {
                head.Append(name).Append(": ").Append(value).Append("\r\n");
            }
            head.Append("\r\n");
            await stream.WriteAsync(Encoding.ASCII.GetBytes(head.ToString()), cancellationToken);
            await stream.WriteAsync(body, cancellationToken);
        };

    public static Handler RespondWithoutContentLength(int status, string body) =>
        async (stream, cancellationToken) =>
        {
            await stream.WriteAsync(
                Encoding.ASCII.GetBytes(string.Create(CultureInfo.InvariantCulture, $"HTTP/1.1 {status} Status\r\nConnection: close\r\n\r\n")),
                cancellationToken
            );
            await stream.WriteAsync(Encoding.UTF8.GetBytes(body), cancellationToken);
        };

    public void Dispose()
    {
        _stop.Cancel();
        _listener.Stop();
        _certificate.Dispose();
        _stop.Dispose();
    }

    private bool Validate(object sender, X509Certificate? certificate, X509Chain? chain, SslPolicyErrors errors)
    {
        if (errors != SslPolicyErrors.RemoteCertificateChainErrors || certificate is null)
        {
            return errors == SslPolicyErrors.None;
        }
        using var customChain = new X509Chain();
        customChain.ChainPolicy.TrustMode = X509ChainTrustMode.CustomRootTrust;
        customChain.ChainPolicy.RevocationMode = X509RevocationMode.NoCheck;
        customChain.ChainPolicy.CustomTrustStore.Add(_certificate);
        return customChain.Build(X509CertificateLoader.LoadCertificate(certificate.GetRawCertData()));
    }

    private async Task AcceptAsync()
    {
        while (!_stop.IsCancellationRequested)
        {
            TcpClient client;
            try
            {
                client = await _listener.AcceptTcpClientAsync(_stop.Token);
            }
            catch (Exception e) when (e is OperationCanceledException or SocketException or ObjectDisposedException)
            {
                return;
            }
            _ = HandleAsync(client);
        }
    }

    private async Task HandleAsync(TcpClient client)
    {
        using (client)
        {
            try
            {
                await using var stream = new SslStream(client.GetStream());
                await stream.AuthenticateAsServerAsync(_certificate);
                var request = await ReadRequestAsync(stream);
                Handler? handler;
                lock (_requests)
                {
                    _requests.Add(request);
                    handler = _handlers.Count > 1 ? _handlers[0] : _handlers.FirstOrDefault();
                    if (_handlers.Count > 1)
                    {
                        _handlers.RemoveAt(0);
                    }
                }
                if (handler is not null)
                {
                    await handler(stream, _stop.Token);
                }
            }
            catch (Exception e) when (e is IOException or OperationCanceledException or System.Security.Authentication.AuthenticationException)
            {
            }
        }
    }

    private static async Task<Request> ReadRequestAsync(Stream stream)
    {
        var data = new List<byte>();
        var buffer = new byte[1];
        while (data.Count < 4 || !(data[^4] == '\r' && data[^3] == '\n' && data[^2] == '\r' && data[^1] == '\n'))
        {
            if (await stream.ReadAsync(buffer) == 0)
            {
                throw new IOException("Connection closed");
            }
            data.Add(buffer[0]);
        }
        var lines = Encoding.ASCII.GetString([.. data]).Split("\r\n", StringSplitOptions.RemoveEmptyEntries);
        var requestLine = lines[0].Split(' ');
        var headers = lines
            .Skip(1)
            .Select(line => line.Split(':', 2))
            .ToDictionary(parts => parts[0], parts => parts[1].Trim(), StringComparer.OrdinalIgnoreCase);
        return new Request(requestLine[0], requestLine[1], headers);
    }
}
