package com.heroku.applink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

final class AuthorizationResponseParser {
  private static final long MAX_EXPIRATION = 253402300799999L;

  private AuthorizationResponseParser() {}

  static String path(String developerName) {
    StringBuilder path = new StringBuilder("/authorizations/");
    for (byte b : developerName.getBytes(StandardCharsets.UTF_8)) {
      char c = (char) (b & 0xFF);
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '-'
          || c == '.'
          || c == '_'
          || c == '~') {
        path.append(c);
      } else {
        path.append('%').append(String.format("%02X", b & 0xFF));
      }
    }
    return path.toString();
  }

  static AuthorizationAppLinkContext parse(byte[] body) {
    JsonObject json = Json.parseObject(body);
    if (json == null) {
      throw new UnexpectedResponseException("The AppLink API response is not a JSON object.");
    }
    AuthorizationStatus status = AuthorizationStatus.fromValue(Json.string(json, "status"));
    if (status == null) {
      throw invalidField("status");
    }
    if (status != AuthorizationStatus.AUTHORIZED) {
      throw new UnusableAuthorizationException(
          "The authorization has the status " + status.value() + " instead of authorized.", status);
    }

    JsonObject org = requiredObject(json, "org", "");
    JsonObject userAuth = requiredObject(org, "user_auth", "org.");
    OrgType type = OrgType.fromValue(Json.string(org, "type"));
    if (type == null) {
      throw invalidField("org.type");
    }

    return new AuthorizationAppLinkContext(
        new Org(
            requiredString(org, "id", "org."),
            requiredString(org, "instance_url", "org."),
            requiredString(org, "api_version", "org."),
            Optional.of(type)),
        new User(
            requiredString(userAuth, "user_id", "org.user_auth."),
            requiredString(userAuth, "username", "org.user_auth.")),
        new AuthorizationMetadata(
            requiredString(json, "id", ""),
            requiredString(org, "developer_name", "org."),
            requiredTimestamp(json, "created_at"),
            requiredString(json, "created_by", ""),
            optionalString(json, "created_via_app"),
            requiredTimestamp(json, "last_modified_at"),
            optionalString(json, "last_modified_by"),
            optionalString(json, "redirect_uri")),
        new Credentials(
            requiredString(userAuth, "access_token", "org.user_auth."),
            optionalExpiration(userAuth)));
  }

  private static JsonObject requiredObject(JsonObject object, String name, String prefix) {
    JsonObject value = Json.object(object, name);
    if (value == null) {
      throw invalidField(prefix + name);
    }
    return value;
  }

  private static String requiredString(JsonObject object, String name, String prefix) {
    String value = Json.string(object, name);
    if (value == null || value.isEmpty()) {
      throw invalidField(prefix + name);
    }
    return value;
  }

  private static Optional<String> optionalString(JsonObject object, String name) {
    if (Json.isAbsent(object, name)) {
      return Optional.empty();
    }
    String value = Json.string(object, name);
    if (value == null) {
      throw invalidField(name);
    }
    return Optional.of(value).filter(s -> !s.isEmpty());
  }

  private static Instant requiredTimestamp(JsonObject object, String name) {
    String value = requiredString(object, name, "");
    Instant instant = null;
    try {
      instant = OffsetDateTime.parse(value).toInstant();
    } catch (DateTimeParseException e) {
      instant = null;
    }
    if (instant == null) {
      throw invalidField(name);
    }
    return instant;
  }

  private static Optional<Instant> optionalExpiration(JsonObject userAuth) {
    if (Json.isAbsent(userAuth, "expiration")) {
      return Optional.empty();
    }
    JsonElement element = userAuth.get("expiration");
    long millis = -1;
    if (element instanceof JsonPrimitive primitive && primitive.isNumber()) {
      try {
        millis = primitive.getAsBigDecimal().longValueExact();
      } catch (ArithmeticException | NumberFormatException e) {
        millis = -1;
      }
    }
    if (millis < 0 || millis > MAX_EXPIRATION) {
      throw invalidField("org.user_auth.expiration");
    }
    return millis == 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(millis));
  }

  private static UnexpectedResponseException invalidField(String field) {
    return new UnexpectedResponseException(
        "The AppLink API response has no valid \"" + field + "\" field.");
  }
}
