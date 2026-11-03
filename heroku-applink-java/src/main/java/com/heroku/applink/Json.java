package com.heroku.applink;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

final class Json {
  private static final TypeAdapter<JsonElement> ELEMENT_ADAPTER =
      new Gson().getAdapter(JsonElement.class);

  private Json() {}

  /** Parses strict UTF-8 JSON into an object, or returns null if that fails. */
  static JsonObject parseObject(byte[] data) {
    try {
      String text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(data))
              .toString();
      JsonReader reader = new JsonReader(new StringReader(text));
      reader.setStrictness(Strictness.STRICT);
      JsonElement element = ELEMENT_ADAPTER.read(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT || !element.isJsonObject()) {
        return null;
      }
      return element.getAsJsonObject();
    } catch (IOException | RuntimeException | StackOverflowError e) {
      return null;
    }
  }

  /** Returns the member as a string, or null if it is missing, null, or not a string. */
  static String string(JsonObject object, String name) {
    JsonElement element = object.get(name);
    if (element instanceof JsonPrimitive primitive && primitive.isString()) {
      return primitive.getAsString();
    }
    return null;
  }

  /** Returns the member as an object, or null if it is missing, null, or not an object. */
  static JsonObject object(JsonObject object, String name) {
    JsonElement element = object.get(name);
    return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
  }

  static boolean isAbsent(JsonObject object, String name) {
    JsonElement element = object.get(name);
    return element == null || element.isJsonNull();
  }
}
