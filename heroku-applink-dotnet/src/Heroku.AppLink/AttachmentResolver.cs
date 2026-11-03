using System.Text.RegularExpressions;

namespace Heroku.AppLink;

internal sealed record ResolvedAttachment(string ApiUrl, string Token, string AppId)
{
    public override string ToString() =>
        $"ResolvedAttachment {{ ApiUrl = {ApiUrl}, Token = [REDACTED], AppId = {AppId} }}";
}

internal static partial class AttachmentResolver
{
    [GeneratedRegex("^[A-Za-z][A-Za-z0-9+.-]*://")]
    private static partial Regex AbsoluteUrlPattern();

    [GeneratedRegex("^[Hh][Tt][Tt][Pp][Ss]://")]
    private static partial Regex HttpsUrlPattern();

    public static ResolvedAttachment Resolve(IReadOnlyDictionary<string, string> env, string? attachment)
    {
        string apiUrlVar;
        string tokenVar;

        if (attachment is null)
        {
            apiUrlVar = "HEROKU_APPLINK_API_URL";
            tokenVar = "HEROKU_APPLINK_TOKEN";
        }
        else if (attachment.Length == 0)
        {
            throw new ConfigurationException("The attachment must not be empty.");
        }
        else if (AbsoluteUrlPattern().IsMatch(attachment))
        {
            var wanted = NormalizeUrl(attachment);
            var matches = env
                .Where(e => e.Key.EndsWith("_API_URL", StringComparison.Ordinal) && NormalizeUrl(e.Value) == wanted)
                .Select(e => e.Key)
                .Order(StringComparer.Ordinal)
                .ToList();
            if (matches.Count == 0)
            {
                throw new ConfigurationException(
                    $"No config var ending in _API_URL matches the attachment URL {attachment}."
                );
            }
            if (matches.Count > 1)
            {
                throw new ConfigurationException(
                    $"Several config vars match the attachment URL {attachment}: {string.Join(", ", matches)}."
                );
            }
            apiUrlVar = matches[0];
            tokenVar = apiUrlVar[..^"API_URL".Length] + "TOKEN";
        }
        else
        {
            var name = AsciiUpperCase(attachment);
            if (IsSet(env, $"{name}_API_URL") && IsSet(env, $"{name}_TOKEN"))
            {
                apiUrlVar = $"{name}_API_URL";
                tokenVar = $"{name}_TOKEN";
            }
            else
            {
                apiUrlVar = $"HEROKU_APPLINK_{name}_API_URL";
                tokenVar = $"HEROKU_APPLINK_{name}_TOKEN";
            }
        }

        var apiUrl = RequiredConfigVar(env, apiUrlVar);
        var token = RequiredConfigVar(env, tokenVar);
        var appId = RequiredConfigVar(env, "HEROKU_APP_ID");
        if (!HttpsUrlPattern().IsMatch(apiUrl))
        {
            throw new ConfigurationException($"The config var {apiUrlVar} must contain an https URL.");
        }
        return new ResolvedAttachment(RemoveTrailingSlash(apiUrl), token, appId);
    }

    private static bool IsSet(IReadOnlyDictionary<string, string> env, string name) =>
        env.TryGetValue(name, out var value) && value.Length > 0;

    private static string RequiredConfigVar(IReadOnlyDictionary<string, string> env, string name)
    {
        if (!env.TryGetValue(name, out var value) || value.Length == 0)
        {
            throw new ConfigurationException($"The config var {name} is missing or empty.");
        }
        return value;
    }

    private static string NormalizeUrl(string url) => RemoveTrailingSlash(AsciiLowerCase(url));

    private static string RemoveTrailingSlash(string value) =>
        value.EndsWith('/') ? value[..^1] : value;

    private static string AsciiUpperCase(string value) =>
        string.Create(value.Length, value, (span, source) =>
        {
            for (var i = 0; i < source.Length; i++)
            {
                span[i] = char.IsAsciiLetterLower(source[i]) ? (char)(source[i] - 32) : source[i];
            }
        });

    private static string AsciiLowerCase(string value) =>
        string.Create(value.Length, value, (span, source) =>
        {
            for (var i = 0; i < source.Length; i++)
            {
                span[i] = char.IsAsciiLetterUpper(source[i]) ? (char)(source[i] + 32) : source[i];
            }
        });
}
