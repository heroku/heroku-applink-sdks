using System.Collections;
using System.Text;

namespace Heroku.AppLink;

/// <summary>
/// The entry point of the Heroku AppLink SDK. Use <see cref="ParseInvocationContext"/> to get the
/// context of an invocation from Salesforce, and <see cref="GetAuthorizationAsync(string, string?, CancellationToken)"/>
/// to look up a stored authorization.
/// </summary>
/// <seealso href="https://devcenter.heroku.com/articles/heroku-applink">Heroku AppLink</seealso>
public static class HerokuAppLink
{
    internal const string Version = "0.1.0";
    internal const string UserAgent = "heroku-applink-dotnet/" + Version;

    /// <summary>
    /// Parses the context of an invocation from Salesforce from the <c>x-client-context</c> header
    /// of an inbound request. The SDK trusts the header, because AppLink's Service Mesh
    /// authenticates requests before they reach the app.
    /// </summary>
    /// <example>
    /// <code>
    /// var context = HerokuAppLink.ParseInvocationContext(name => request.Headers[name]);
    /// </code>
    /// </example>
    /// <param name="getHeader">
    /// Returns the value of the inbound request's header with the given name, or
    /// <see langword="null"/> if the request doesn't have it. The SDK always passes header names in
    /// lower case.
    /// </param>
    /// <returns>The context of the invocation.</returns>
    /// <exception cref="InvalidInboundDataException">The header is missing or malformed.</exception>
    /// <seealso href="https://devcenter.heroku.com/articles/invoking-heroku-applink-apps">Invoking Heroku AppLink Apps</seealso>
    public static InvocationAppLinkContext ParseInvocationContext(Func<string, string?> getHeader)
    {
        ArgumentNullException.ThrowIfNull(getHeader);
        return InvocationContextParser.Parse(getHeader);
    }

    /// <summary>
    /// Looks up a stored authorization through the AppLink API, with a new request on every call.
    /// Apps can keep the returned context and reuse it until its credentials expire.
    /// </summary>
    /// <example>
    /// <code>
    /// var context = await HerokuAppLink.GetAuthorizationAsync("&lt;my-authorization&gt;");
    /// </code>
    /// With another attachment:
    /// <code>
    /// // By name, reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
    /// var context = await HerokuAppLink.GetAuthorizationAsync("&lt;my-authorization&gt;", attachment: "HEROKU_APPLINK_PURPLE");
    ///
    /// // By color, reads the same config vars as the name
    /// var context = await HerokuAppLink.GetAuthorizationAsync("&lt;my-authorization&gt;", attachment: "purple");
    ///
    /// // By API URL, uses the attachment whose _API_URL config var holds that URL
    /// var apiUrl = Environment.GetEnvironmentVariable("HEROKU_APPLINK_PURPLE_API_URL")
    ///     ?? throw new InvalidOperationException("HEROKU_APPLINK_PURPLE_API_URL is not set.");
    /// var context = await HerokuAppLink.GetAuthorizationAsync("&lt;my-authorization&gt;", attachment: apiUrl);
    /// </code>
    /// </example>
    /// <param name="developerName">The developer name of the authorization.</param>
    /// <param name="attachment">
    /// The AppLink add-on attachment to use, by name (such as <c>HEROKU_APPLINK_PURPLE</c>), color
    /// (such as <c>purple</c>), or API URL. Defaults to <c>HEROKU_APPLINK</c>.
    /// </param>
    /// <param name="cancellationToken">Cancels the lookup.</param>
    /// <returns>The context of the authorization.</returns>
    /// <exception cref="ConfigurationException">The developer name or attachment is empty, or the config vars are missing or invalid.</exception>
    /// <exception cref="TransportException">The request to the AppLink API didn't complete.</exception>
    /// <exception cref="HttpException">The AppLink API responded with a status other than 2xx.</exception>
    /// <exception cref="UnusableAuthorizationException">The authorization isn't authorized.</exception>
    /// <exception cref="UnexpectedResponseException">The response doesn't have the expected content.</exception>
    /// <seealso href="https://devcenter.heroku.com/articles/heroku-applink-api">Heroku AppLink API</seealso>
    public static Task<AuthorizationAppLinkContext> GetAuthorizationAsync(
        string developerName,
        string? attachment = null,
        CancellationToken cancellationToken = default
    )
    {
        ArgumentNullException.ThrowIfNull(developerName);
        return GetAuthorizationAsync(ReadEnvironment(), HttpSettings.Default, developerName, attachment, cancellationToken);
    }

    internal static async Task<AuthorizationAppLinkContext> GetAuthorizationAsync(
        IReadOnlyDictionary<string, string> env,
        HttpSettings settings,
        string developerName,
        string? attachment,
        CancellationToken cancellationToken
    )
    {
        if (developerName.Length == 0)
        {
            throw new ConfigurationException("The developer name must not be empty.");
        }
        var resolved = AttachmentResolver.Resolve(env, attachment);
        if (
            !Uri.TryCreate(
                resolved.ApiUrl + AuthorizationResponseParser.Path(developerName),
                new UriCreationOptions { DangerousDisablePathAndQueryCanonicalization = true },
                out var uri
            )
        )
        {
            throw new ConfigurationException("The AppLink API URL is not a valid URL.");
        }

        var response = await Http.GetWithRetriesAsync(
                settings,
                uri,
                new Dictionary<string, string>
                {
                    ["Authorization"] = "Bearer " + resolved.Token,
                    ["X-App-UUID"] = resolved.AppId,
                    ["Accept"] = "application/json",
                    ["User-Agent"] = UserAgent,
                },
                cancellationToken
            )
            .ConfigureAwait(false);

        if (response.Status is < 200 or > 299)
        {
            throw new HttpException(
                $"The AppLink API responded with HTTP status {response.Status}.",
                response.Status,
                Encoding.UTF8.GetString(response.Body.Span)
            );
        }
        if (response.BodyTooLarge)
        {
            throw new UnexpectedResponseException("The AppLink API response is larger than 1 MiB.");
        }
        return AuthorizationResponseParser.Parse(response.Body);
    }

    private static Dictionary<string, string> ReadEnvironment()
    {
        var env = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (DictionaryEntry entry in Environment.GetEnvironmentVariables())
        {
            env[(string)entry.Key] = entry.Value as string ?? "";
        }
        return env;
    }
}
