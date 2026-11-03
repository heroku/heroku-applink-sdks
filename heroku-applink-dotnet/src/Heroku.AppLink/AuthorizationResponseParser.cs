using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Heroku.AppLink;

internal static partial class AuthorizationResponseParser
{
    private const ulong MaxExpiration = 253402300799999;

    [GeneratedRegex(@"(?:[Zz]|[+-][0-9]{2}:[0-9]{2})\z")]
    private static partial Regex OffsetPattern();

    public static string Path(string developerName)
    {
        var builder = new StringBuilder("/authorizations/");
        foreach (var b in Encoding.UTF8.GetBytes(developerName))
        {
            if (char.IsAsciiLetterOrDigit((char)b) || b is (byte)'-' or (byte)'.' or (byte)'_' or (byte)'~')
            {
                builder.Append((char)b);
            }
            else
            {
                builder.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
            }
        }
        return builder.ToString();
    }

    public static AuthorizationAppLinkContext Parse(ReadOnlyMemory<byte> body)
    {
        if (Json.ParseObject(body) is not { } data)
        {
            throw new UnexpectedResponseException("The AppLink API response is not a JSON object.");
        }
        if (!Json.TryGetString(Json.Get(data, "status"), out var statusValue) || ParseStatus(statusValue) is not { } status)
        {
            throw InvalidField("status");
        }
        if (status != AuthorizationStatus.Authorized)
        {
            throw new UnusableAuthorizationException(
                $"The authorization has the status {statusValue} instead of authorized.",
                status
            );
        }

        var org = RequiredObject(data, "org");
        var userAuth = RequiredObject(org, "user_auth", "org.");
        if (!Json.TryGetString(Json.Get(org, "type"), out var typeValue) || ParseOrgType(typeValue) is not { } orgType)
        {
            throw InvalidField("org.type");
        }

        return new AuthorizationAppLinkContext(
            Org: new Org(
                Id: RequiredString(org, "id", "org."),
                DomainUrl: RequiredString(org, "instance_url", "org."),
                ApiVersion: RequiredString(org, "api_version", "org."),
                Type: orgType
            ),
            User: new User(
                Id: RequiredString(userAuth, "user_id", "org.user_auth."),
                Username: RequiredString(userAuth, "username", "org.user_auth.")
            ),
            AuthorizationMetadata: new AuthorizationMetadata(
                Id: RequiredString(data, "id"),
                DeveloperName: RequiredString(org, "developer_name", "org."),
                CreatedAt: RequiredTimestamp(data, "created_at"),
                CreatedBy: RequiredString(data, "created_by"),
                CreatedViaApp: OptionalString(data, "created_via_app"),
                LastModifiedAt: RequiredTimestamp(data, "last_modified_at"),
                LastModifiedBy: OptionalString(data, "last_modified_by"),
                RedirectUri: OptionalString(data, "redirect_uri")
            ),
            Credentials: new Credentials(
                AccessToken: RequiredString(userAuth, "access_token", "org.user_auth."),
                Expiration: OptionalExpiration(userAuth)
            )
        );
    }

    private static AuthorizationStatus? ParseStatus(string value) =>
        value switch
        {
            "authorizing" => AuthorizationStatus.Authorizing,
            "authorized" => AuthorizationStatus.Authorized,
            "disconnecting" => AuthorizationStatus.Disconnecting,
            "disconnected" => AuthorizationStatus.Disconnected,
            "failed" => AuthorizationStatus.Failed,
            _ => null,
        };

    private static OrgType? ParseOrgType(string value) =>
        value switch
        {
            "SalesforceOrg" => OrgType.SalesforceOrg,
            "DataCloudOrg" => OrgType.DataCloudOrg,
            _ => null,
        };

    private static JsonElement RequiredObject(JsonElement obj, string name, string prefix = "")
    {
        if (!Json.IsObject(Json.Get(obj, name), out var value))
        {
            throw InvalidField(prefix + name);
        }
        return value;
    }

    private static string RequiredString(JsonElement obj, string name, string prefix = "")
    {
        if (!Json.TryGetString(Json.Get(obj, name), out var value) || value.Length == 0)
        {
            throw InvalidField(prefix + name);
        }
        return value;
    }

    private static string? OptionalString(JsonElement obj, string name)
    {
        var element = Json.Get(obj, name);
        if (element is null)
        {
            return null;
        }
        if (!Json.TryGetString(element, out var value))
        {
            throw InvalidField(name);
        }
        return value.Length == 0 ? null : value;
    }

    private static DateTimeOffset RequiredTimestamp(JsonElement obj, string name)
    {
        var value = RequiredString(obj, name);
        if (
            !OffsetPattern().IsMatch(value)
            || !DateTimeOffset.TryParse(
                value,
                CultureInfo.InvariantCulture,
                DateTimeStyles.AdjustToUniversal,
                out var timestamp
            )
        )
        {
            throw InvalidField(name);
        }
        return timestamp.ToUniversalTime();
    }

    private static DateTimeOffset? OptionalExpiration(JsonElement userAuth)
    {
        if (Json.Get(userAuth, "expiration") is not { } element)
        {
            return null;
        }
        var raw = element.ValueKind == JsonValueKind.Number ? element.GetRawText() : "";
        if (
            !ulong.TryParse(raw, NumberStyles.None, CultureInfo.InvariantCulture, out var millis)
            || millis > MaxExpiration
        )
        {
            throw InvalidField("org.user_auth.expiration");
        }
        return millis == 0 ? null : DateTimeOffset.FromUnixTimeMilliseconds((long)millis);
    }

    private static UnexpectedResponseException InvalidField(string field) =>
        new($"The AppLink API response has no valid \"{field}\" field.");
}
