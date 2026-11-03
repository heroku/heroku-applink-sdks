using System.Collections.Concurrent;
using System.Text.Json;

namespace Heroku.AppLink.Tests;

internal static class Vectors
{
    private static readonly string Directory = FindDirectory();
    private static readonly ConcurrentDictionary<string, IReadOnlyList<JsonElement>> Cache = new(StringComparer.Ordinal);

    public static IReadOnlyList<JsonElement> Load(string file) => Cache.GetOrAdd(file, Parse);

    private static IReadOnlyList<JsonElement> Parse(string file)
    {
        using var document = JsonDocument.Parse(File.ReadAllBytes(Path.Combine(Directory, file)));
        return [.. document.RootElement.GetProperty("cases").EnumerateArray().Select(c => c.Clone())];
    }

    public static TheoryData<string> Names(string file, string nameField = "name") =>
        [.. Load(file).Select(c => c.GetProperty(nameField).GetString()!)];

    public static JsonElement Find(string file, string name, string nameField = "name") =>
        Load(file).Single(c => c.GetProperty(nameField).GetString() == name);

    public static JsonElement? Get(this JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null ? value : null;

    public static string? String(this JsonElement element, string name) => element.Get(name)?.GetString();

    public static DateTimeOffset? Instant(this JsonElement element, string name) =>
        element.String(name) is { } value ? DateTimeOffset.Parse(value, System.Globalization.CultureInfo.InvariantCulture) : null;

    private static string FindDirectory()
    {
        for (var directory = new DirectoryInfo(AppContext.BaseDirectory); directory is not null; directory = directory.Parent)
        {
            var candidate = Path.Combine(directory.FullName, "test-vectors");
            if (System.IO.Directory.Exists(candidate))
            {
                return candidate;
            }
        }
        throw new DirectoryNotFoundException("test-vectors");
    }
}
