using System.Text;

namespace Heroku.AppLink.Tests;

public class AuthorizationTests
{
    private const string ResponseFile = "authorization-response.json";
    private const string AttachmentFile = "attachment-resolution.json";
    private const string PathFile = "request-path.json";

    public static TheoryData<string> ResponseCases => Vectors.Names(ResponseFile);
    public static TheoryData<string> AttachmentCases => Vectors.Names(AttachmentFile);
    public static TheoryData<string> PathCases => Vectors.Names(PathFile, "developerName");

    [Theory]
    [MemberData(nameof(ResponseCases))]
    public void ResponseVectors(string name)
    {
        var vector = Vectors.Find(ResponseFile, name);
        var body = Encoding.UTF8.GetBytes(vector.String("body")!);
        var error = vector.String("error");
        var hasExpected = vector.Get("expected") is not null;
        if (error == "unusable_authorization" && !hasExpected)
        {
            var unusable = Assert.Throws<UnusableAuthorizationException>(() => AuthorizationResponseParser.Parse(body));
            Assert.Equal(vector.String("status"), unusable.Status.ToString(), ignoreCase: true);
            return;
        }
        if (!hasExpected)
        {
            var unexpected = Assert.Throws<UnexpectedResponseException>(() => AuthorizationResponseParser.Parse(body));
            Assert.Null(unexpected.InnerException);
            return;
        }

        AuthorizationAppLinkContext context;
        try
        {
            context = AuthorizationResponseParser.Parse(body);
        }
        catch (UnusableAuthorizationException e) when (error == "unusable_authorization")
        {
            Assert.Equal(vector.String("status"), e.Status.ToString(), ignoreCase: true);
            return;
        }
        catch (UnexpectedResponseException e) when (error == "unexpected_response")
        {
            Assert.Null(e.InnerException);
            return;
        }

        var expected = vector.GetProperty("expected");
        var metadata = expected.GetProperty("authorizationMetadata");
        var org = expected.GetProperty("org");
        var user = expected.GetProperty("user");
        var credentials = expected.GetProperty("credentials");
        Assert.Equal(
            new AuthorizationMetadata(
                metadata.String("id")!,
                metadata.String("developerName")!,
                metadata.Instant("createdAt")!.Value,
                metadata.String("createdBy")!,
                metadata.String("createdViaApp"),
                metadata.Instant("lastModifiedAt")!.Value,
                metadata.String("lastModifiedBy"),
                metadata.String("redirectUri")
            ),
            context.AuthorizationMetadata
        );
        Assert.Equal(TimeSpan.Zero, context.AuthorizationMetadata.CreatedAt.Offset);
        Assert.Equal(
            new Org(
                org.String("id")!,
                org.String("domainUrl")!,
                org.String("apiVersion")!,
                Enum.Parse<OrgType>(org.String("type")!)
            ),
            context.Org
        );
        Assert.Equal(new User(user.String("id")!, user.String("username")!), context.User);
        Assert.Equal(
            new Credentials(credentials.String("accessToken")!, credentials.Instant("expiration")),
            context.Credentials
        );
    }

    [Fact]
    public void ResponseErrorsDontLeakTheBody()
    {
        var body = """{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}"""u8.ToArray();
        var e = Assert.Throws<UnexpectedResponseException>(() => AuthorizationResponseParser.Parse(body));
        Assert.DoesNotContain("secret-token", e.Message, StringComparison.Ordinal);
    }

    [Theory]
    [MemberData(nameof(AttachmentCases))]
    public void AttachmentVectors(string name)
    {
        var vector = Vectors.Find(AttachmentFile, name);
        var env = vector
            .GetProperty("env")
            .EnumerateObject()
            .ToDictionary(p => p.Name, p => p.Value.GetString()!, StringComparer.Ordinal);
        var attachment = vector.String("attachment");
        if (vector.Get("expected") is not { } expected)
        {
            Assert.Throws<ConfigurationException>(() => AttachmentResolver.Resolve(env, attachment));
            return;
        }
        Assert.Equal(
            new ResolvedAttachment(expected.String("apiUrl")!, expected.String("token")!, expected.String("appId")!),
            AttachmentResolver.Resolve(env, attachment)
        );
    }

    [Theory]
    [MemberData(nameof(PathCases))]
    public void PathVectors(string developerName)
    {
        var vector = Vectors.Find(PathFile, developerName, "developerName");
        Assert.Equal(vector.String("path"), AuthorizationResponseParser.Path(developerName));
    }
}
