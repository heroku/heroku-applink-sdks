namespace Heroku.AppLink;

internal sealed record HttpSettings(HttpMessageInvoker Client, TimeSpan Timeout, IReadOnlyList<TimeSpan> RetryDelays)
{
    public static HttpSettings Default { get; } =
        new(
            new HttpMessageInvoker(
                new SocketsHttpHandler
                {
                    AllowAutoRedirect = false,
                    PooledConnectionLifetime = TimeSpan.FromMinutes(5),
                }
            ),
            TimeSpan.FromSeconds(5),
            [TimeSpan.FromMilliseconds(250), TimeSpan.FromMilliseconds(500), TimeSpan.FromSeconds(1)]
        );
}

internal sealed record HttpResponse(int Status, ReadOnlyMemory<byte> Body, bool BodyTooLarge);

internal static class Http
{
    public const int MaxBodyBytes = 1024 * 1024;
    private const int InitialBufferBytes = 16 * 1024;

    public static async Task<HttpResponse> GetWithRetriesAsync(
        HttpSettings settings,
        Uri uri,
        IReadOnlyDictionary<string, string> headers,
        CancellationToken cancellationToken
    )
    {
        for (var attempt = 0; ; attempt++)
        {
            HttpResponse? response = null;
            TransportException? error = null;
            try
            {
                response = await GetAsync(settings, uri, headers, cancellationToken).ConfigureAwait(false);
            }
            catch (TransportException e)
            {
                error = e;
            }

            var retryable = response is null || response.Status == 429 || response.Status is >= 500 and <= 599;
            if (attempt >= settings.RetryDelays.Count || !retryable)
            {
                return response ?? throw error!;
            }
            await Task.Delay(settings.RetryDelays[attempt], cancellationToken).ConfigureAwait(false);
        }
    }

    private static async Task<HttpResponse> GetAsync(
        HttpSettings settings,
        Uri uri,
        IReadOnlyDictionary<string, string> headers,
        CancellationToken cancellationToken
    )
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        timeout.CancelAfter(settings.Timeout);
        using var request = new HttpRequestMessage(HttpMethod.Get, uri);
        foreach (var (name, value) in headers)
        {
            request.Headers.TryAddWithoutValidation(name, value);
        }

        try
        {
            using var response = await settings
                .Client.SendAsync(request, timeout.Token)
                .ConfigureAwait(false);
            var stream = await response.Content.ReadAsStreamAsync(timeout.Token).ConfigureAwait(false);
            await using (stream.ConfigureAwait(false))
            {
                // One byte more than the expected length, so the read that detects the end of the
                // body doesn't need to grow the buffer.
                var expectedLength = response.Content.Headers.ContentLength ?? InitialBufferBytes;
                var buffer = new byte[(int)Math.Clamp(expectedLength + 1, 1, MaxBodyBytes + 1)];
                var length = 0;
                while (length <= MaxBodyBytes)
                {
                    if (length == buffer.Length)
                    {
                        Array.Resize(ref buffer, Math.Min(buffer.Length * 2, MaxBodyBytes + 1));
                    }
                    var read = await stream.ReadAsync(buffer.AsMemory(length), timeout.Token).ConfigureAwait(false);
                    if (read == 0)
                    {
                        break;
                    }
                    length += read;
                }
                return new HttpResponse(
                    (int)response.StatusCode,
                    buffer.AsMemory(0, Math.Min(length, MaxBodyBytes)),
                    length > MaxBodyBytes
                );
            }
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            throw new TransportException("The request to the AppLink API timed out.", null);
        }
        catch (Exception e) when (e is HttpRequestException or IOException)
        {
            throw new TransportException($"The request to the AppLink API failed: {e.Message}", e);
        }
    }
}
