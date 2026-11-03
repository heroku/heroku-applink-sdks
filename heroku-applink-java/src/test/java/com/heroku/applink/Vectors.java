package com.heroku.applink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.params.provider.Arguments;

final class Vectors {
  private Vectors() {}

  static List<JsonObject> load(String name) {
    try {
      String json = Files.readString(Path.of("..", "test-vectors", name));
      return JsonParser.parseString(json)
          .getAsJsonObject()
          .getAsJsonArray("cases")
          .asList()
          .stream()
          .map(JsonElement::getAsJsonObject)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Arguments> arguments(String name, String nameField) {
    return load(name).stream()
        .<Arguments>map(v -> Arguments.argumentSet(v.get(nameField).getAsString(), v))
        .toList();
  }

  static String string(JsonObject object, String name) {
    JsonElement value = object.get(name);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  static Optional<String> optionalString(JsonObject object, String name) {
    return Optional.ofNullable(string(object, name));
  }

  static Optional<Instant> optionalInstant(JsonObject object, String name) {
    return optionalString(object, name).map(Instant::parse);
  }

  static Map<String, String> stringMap(JsonObject object) {
    return object.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getAsString()));
  }
}
