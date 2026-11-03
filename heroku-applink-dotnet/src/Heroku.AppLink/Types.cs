using System.Text;

namespace Heroku.AppLink;

/// <summary>The type of a Salesforce org, as returned by the AppLink API.</summary>
public enum OrgType
{
    /// <summary>A Salesforce org (<c>SalesforceOrg</c>).</summary>
    SalesforceOrg,

    /// <summary>A Data 360 org (<c>DataCloudOrg</c>).</summary>
    DataCloudOrg,
}

/// <summary>The status of an AppLink authorization.</summary>
public enum AuthorizationStatus
{
    /// <summary>The authorization is being authorized (<c>authorizing</c>).</summary>
    Authorizing,

    /// <summary>The authorization is usable (<c>authorized</c>).</summary>
    Authorized,

    /// <summary>The authorization is being disconnected (<c>disconnecting</c>).</summary>
    Disconnecting,

    /// <summary>The authorization was disconnected (<c>disconnected</c>).</summary>
    Disconnected,

    /// <summary>Authorizing failed with an unexpected error (<c>failed</c>).</summary>
    Failed,
}

/// <summary>A Salesforce org.</summary>
/// <param name="Id">The ID of the org.</param>
/// <param name="DomainUrl">The URL of the org's My Domain, such as <c>https://example.my.salesforce.com</c>. Use it as the instance URL of a Salesforce client.</param>
/// <param name="ApiVersion">The Salesforce API version to use, such as <c>62.0</c>.</param>
/// <param name="Type">The type of the org, or <see langword="null"/> if it isn't known.</param>
public sealed record Org(string Id, string DomainUrl, string ApiVersion, OrgType? Type);

/// <summary>A Salesforce user.</summary>
/// <param name="Id">The ID of the user.</param>
/// <param name="Username">The username of the user.</param>
public sealed record User(string Id, string Username);

/// <summary>
/// Credentials for calling Salesforce APIs. <see cref="ToString"/> redacts the access token, so it
/// doesn't leak into logs by accident.
/// </summary>
/// <param name="AccessToken">The Salesforce access token. Pass it to a Salesforce client as the bearer token.</param>
/// <param name="Expiration">The instant the access token expires, in UTC, or <see langword="null"/> if AppLink doesn't provide one.</param>
public sealed record Credentials(string AccessToken, DateTimeOffset? Expiration)
{
    private bool PrintMembers(StringBuilder builder)
    {
        builder.Append("AccessToken = [REDACTED], Expiration = ").Append(Expiration);
        return true;
    }
}

/// <summary>The org and user an app acts for.</summary>
/// <param name="Org">The Salesforce org.</param>
/// <param name="User">The Salesforce user.</param>
public abstract record AppLinkContext(Org Org, User User);

/// <summary>The context of an invocation from Salesforce.</summary>
/// <param name="Org">The org the invocation comes from.</param>
/// <param name="User">The user who invoked the app.</param>
/// <param name="RequestId">The ID of the invocation.</param>
/// <param name="Namespace">The invoking org's namespace prefix, or <see langword="null"/> if the org has none.</param>
/// <param name="Credentials">Credentials for the invoking user, or <see langword="null"/> if the invocation doesn't carry any.</param>
public sealed record InvocationAppLinkContext(
    Org Org,
    User User,
    string RequestId,
    string? Namespace,
    Credentials? Credentials
) : AppLinkContext(Org, User);

/// <summary>Information about an AppLink authorization.</summary>
/// <param name="Id">The ID of the authorization.</param>
/// <param name="DeveloperName">The developer name of the authorization.</param>
/// <param name="CreatedAt">The instant the authorization was created, in UTC.</param>
/// <param name="CreatedBy">Who created the authorization.</param>
/// <param name="CreatedViaApp">The app the authorization was created with, if known.</param>
/// <param name="LastModifiedAt">The instant the authorization was last modified, in UTC.</param>
/// <param name="LastModifiedBy">Who last modified the authorization, if known.</param>
/// <param name="RedirectUri">The redirect URI of the authorization, if any.</param>
public sealed record AuthorizationMetadata(
    string Id,
    string DeveloperName,
    DateTimeOffset CreatedAt,
    string CreatedBy,
    string? CreatedViaApp,
    DateTimeOffset LastModifiedAt,
    string? LastModifiedBy,
    string? RedirectUri
);

/// <summary>The context of a stored AppLink authorization.</summary>
/// <param name="Org">The authorized org.</param>
/// <param name="User">The authorized user.</param>
/// <param name="AuthorizationMetadata">Information about the authorization.</param>
/// <param name="Credentials">Credentials for the authorized user.</param>
public sealed record AuthorizationAppLinkContext(
    Org Org,
    User User,
    AuthorizationMetadata AuthorizationMetadata,
    Credentials Credentials
) : AppLinkContext(Org, User);
