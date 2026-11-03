namespace Heroku.AppLink;

/// <summary>The common base class of all exceptions the SDK throws.</summary>
public abstract class AppLinkException : Exception
{
    private protected AppLinkException(string message, Exception? innerException = null)
        : base(message, innerException) { }
}

/// <summary>
/// Data sent to the app is missing or can't be parsed, for example a missing or malformed
/// <c>x-client-context</c> header.
/// </summary>
public sealed class InvalidInboundDataException : AppLinkException
{
    internal InvalidInboundDataException(string message)
        : base(message) { }
}

/// <summary>
/// An authorization lookup can't start, because its input or the app's config vars are missing or
/// invalid. Thrown before any request is sent.
/// </summary>
public sealed class ConfigurationException : AppLinkException
{
    internal ConfigurationException(string message)
        : base(message) { }
}

/// <summary>
/// A request to the AppLink API didn't complete, for example because the connection failed or
/// timed out.
/// </summary>
public sealed class TransportException : AppLinkException
{
    internal TransportException(string message, Exception? innerException)
        : base(message, innerException) { }
}

/// <summary>The AppLink API responded with a status other than 2xx.</summary>
public sealed class HttpException : AppLinkException
{
    internal HttpException(string message, int status, string body)
        : base(message)
    {
        Status = status;
        Body = body;
    }

    /// <summary>The HTTP status of the response, such as <c>404</c> for an unknown authorization.</summary>
    public int Status { get; }

    /// <summary>The response body as UTF-8 text, with invalid bytes replaced. At most 1 MiB is read.</summary>
    public string Body { get; }
}

/// <summary>
/// The AppLink API returned the authorization, but it isn't
/// <see cref="AuthorizationStatus.Authorized"/>, for example because it is still being authorized
/// or was disconnected.
/// </summary>
public sealed class UnusableAuthorizationException : AppLinkException
{
    internal UnusableAuthorizationException(string message, AuthorizationStatus status)
        : base(message)
    {
        Status = status;
    }

    /// <summary>The status of the authorization.</summary>
    public AuthorizationStatus Status { get; }
}

/// <summary>
/// The AppLink API responded with a 2xx status, but unexpected content, for example a response
/// with a missing required field.
/// </summary>
public sealed class UnexpectedResponseException : AppLinkException
{
    internal UnexpectedResponseException(string message)
        : base(message) { }
}
