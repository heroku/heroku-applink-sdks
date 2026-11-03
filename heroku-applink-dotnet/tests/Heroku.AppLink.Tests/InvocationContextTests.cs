using System.Text;

namespace Heroku.AppLink.Tests;

public class InvocationContextTests
{
    private const string File = "invocation-context.json";

    public static TheoryData<string> Cases => Vectors.Names(File);

    [Theory]
    [MemberData(nameof(Cases))]
    public void MatchesVector(string name)
    {
        var vector = Vectors.Find(File, name);
        var header = vector.String("header");
        if (vector.Get("expected") is not { } expected)
        {
            var e = Assert.Throws<InvalidInboundDataException>(() => HerokuAppLink.ParseInvocationContext(_ => header));
            Assert.Null(e.InnerException);
            return;
        }

        InvocationAppLinkContext context;
        try
        {
            context = HerokuAppLink.ParseInvocationContext(_ => header);
        }
        catch (InvalidInboundDataException e) when (vector.Get("error") is not null)
        {
            Assert.Null(e.InnerException);
            return;
        }

        var org = expected.GetProperty("org");
        var user = expected.GetProperty("user");
        Assert.Equal(expected.String("requestId"), context.RequestId);
        Assert.Equal(expected.String("namespace"), context.Namespace);
        Assert.Equal(new Org(org.String("id")!, org.String("domainUrl")!, org.String("apiVersion")!, null), context.Org);
        Assert.Equal(new User(user.String("id")!, user.String("username")!), context.User);
        if (expected.Get("credentials") is { } credentials)
        {
            Assert.Equal(new Credentials(credentials.String("accessToken")!, null), context.Credentials);
        }
        else
        {
            Assert.Null(context.Credentials);
        }
    }

    [Fact]
    public void AsksForTheHeaderInLowerCase()
    {
        var names = new List<string>();
        Assert.Throws<InvalidInboundDataException>(() =>
            HerokuAppLink.ParseInvocationContext(name =>
            {
                names.Add(name);
                return null;
            })
        );
        Assert.Equal(["x-client-context"], names);
    }

    [Fact]
    public void ErrorsDontLeakTheHeader()
    {
        var header = Convert.ToBase64String(Encoding.UTF8.GetBytes("{\"accessToken\":\"secret-token\",\"version\":\"1.0\""));
        var e = Assert.Throws<InvalidInboundDataException>(() => HerokuAppLink.ParseInvocationContext(_ => header));
        Assert.DoesNotContain("secret-token", e.Message, StringComparison.Ordinal);
        Assert.Null(e.InnerException);
        Assert.IsAssignableFrom<AppLinkException>(e);
    }
}
