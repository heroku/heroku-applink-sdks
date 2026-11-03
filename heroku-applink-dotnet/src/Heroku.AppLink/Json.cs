using System.Text.Json;
using System.Text.Unicode;

namespace Heroku.AppLink;

internal static class Json
{
    private static readonly JsonDocumentOptions Options = new()
    {
        AllowTrailingCommas = false,
        CommentHandling = JsonCommentHandling.Disallow,
    };

    public static JsonElement? ParseObject(ReadOnlyMemory<byte> data)
    {
        if (!Utf8.IsValid(data.Span))
        {
            return null;
        }
        try
        {
            using var document = JsonDocument.Parse(data, Options);
            return document.RootElement.ValueKind == JsonValueKind.Object
                ? document.RootElement.Clone()
                : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    public static JsonElement? Get(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var value) && value.ValueKind != JsonValueKind.Null
            ? value
            : null;

    public static bool TryGetString(JsonElement? value, out string result)
    {
        result = "";
        if (value is not { ValueKind: JsonValueKind.String } element)
        {
            return false;
        }
        try
        {
            result = element.GetString()!;
            return true;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    public static bool IsObject(JsonElement? value, out JsonElement result)
    {
        result = value ?? default;
        return value is { ValueKind: JsonValueKind.Object };
    }
}
