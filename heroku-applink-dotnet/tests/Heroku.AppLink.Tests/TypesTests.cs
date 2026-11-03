using System.Xml.Linq;

namespace Heroku.AppLink.Tests;

public class TypesTests
{
    private static readonly Credentials Credentials = new("secret-token", new DateTimeOffset(2026, 10, 13, 9, 15, 42, TimeSpan.Zero));

    [Fact]
    public void CredentialsToStringRedactsTheAccessToken()
    {
        Assert.DoesNotContain("secret-token", Credentials.ToString(), StringComparison.Ordinal);
        Assert.StartsWith("Credentials { AccessToken = [REDACTED], Expiration = ", Credentials.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void ContextToStringRedactsTheAccessToken()
    {
        AppLinkContext context = new InvocationAppLinkContext(
            new Org("org", "https://example.tld", "62.0", null),
            new User("user", "user@example.tld"),
            "request",
            null,
            Credentials
        );
        Assert.DoesNotContain("secret-token", context.ToString(), StringComparison.Ordinal);
        Assert.Contains("[REDACTED]", context.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void ResolvedAttachmentToStringRedactsTheToken()
    {
        var attachment = new ResolvedAttachment("https://example.tld", "secret-token", "app");
        Assert.DoesNotContain("secret-token", attachment.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void UserAgentMatchesThePackageVersion()
    {
        var directory = new DirectoryInfo(AppContext.BaseDirectory);
        while (!File.Exists(Path.Combine(directory.FullName, "Heroku.AppLink.slnx")))
        {
            directory = directory.Parent!;
        }
        var project = XDocument.Load(Path.Combine(directory.FullName, "src", "Heroku.AppLink", "Heroku.AppLink.csproj"));
        Assert.Equal(HerokuAppLink.Version, project.Descendants("Version").Single().Value);
        Assert.Equal($"heroku-applink-dotnet/{HerokuAppLink.Version}", HerokuAppLink.UserAgent);
    }
}
