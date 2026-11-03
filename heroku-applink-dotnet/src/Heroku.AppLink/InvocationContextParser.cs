using System.Text.Json;

namespace Heroku.AppLink;

internal static class InvocationContextParser
{
    public const string HeaderName = "x-client-context";

    public static InvocationAppLinkContext Parse(Func<string, string?> getHeader)
    {
        var header = getHeader(HeaderName);
        if (string.IsNullOrEmpty(header))
        {
            throw new InvalidInboundDataException($"The {HeaderName} header is missing.");
        }

        byte[] decoded;
        try
        {
            decoded = Convert.FromBase64String(header);
        }
        catch (FormatException)
        {
            throw new InvalidInboundDataException($"The {HeaderName} header is not valid base64.");
        }

        if (Json.ParseObject(decoded) is not { } data)
        {
            throw new InvalidInboundDataException(
                $"The {HeaderName} header doesn't contain a UTF-8 JSON object."
            );
        }
        if (!Json.TryGetString(Json.Get(data, "version"), out var version) || version != "1.0")
        {
            throw new InvalidInboundDataException(
                $"The {HeaderName} header doesn't have the supported version 1.0."
            );
        }

        if (!Json.IsObject(Json.Get(data, "userContext"), out var userContext))
        {
            throw InvalidField("userContext");
        }

        var accessTokenValue = Json.Get(data, "accessToken");
        string? accessToken = null;
        if (
            accessTokenValue is not null
            && (!Json.TryGetString(accessTokenValue, out accessToken) || accessToken.Length == 0)
        )
        {
            throw InvalidField("accessToken");
        }
        var namespaceValue = Json.Get(data, "namespace");
        string? ns = null;
        if (namespaceValue is not null && !Json.TryGetString(namespaceValue, out ns))
        {
            throw InvalidField("namespace");
        }

        return new InvocationAppLinkContext(
            Org: new Org(
                Id: RequiredString(data, "orgId"),
                DomainUrl: RequiredString(data, "orgDomainUrl"),
                ApiVersion: RequiredString(data, "apiVersion"),
                Type: null
            ),
            User: new User(
                Id: RequiredString(userContext, "userId", "userContext."),
                Username: RequiredString(userContext, "username", "userContext.")
            ),
            RequestId: RequiredString(data, "requestId"),
            Namespace: string.IsNullOrEmpty(ns) ? null : ns,
            Credentials: accessToken is null ? null : new Credentials(accessToken, null)
        );
    }

    private static string RequiredString(JsonElement obj, string name, string prefix = "")
    {
        if (!Json.TryGetString(Json.Get(obj, name), out var value) || value.Length == 0)
        {
            throw InvalidField(prefix + name);
        }
        return value;
    }

    private static InvalidInboundDataException InvalidField(string field) =>
        new($"The {HeaderName} header has no valid \"{field}\" field.");
}
