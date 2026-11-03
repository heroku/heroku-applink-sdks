package com.heroku.applink;

import com.google.gson.JsonObject;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;

final class InvocationContextParser {
  static final String HEADER_NAME = "x-client-context";

  private InvocationContextParser() {}

  static InvocationAppLinkContext parse(Function<String, String> getHeader) {
    String value = getHeader.apply(HEADER_NAME);
    if (value == null || value.isEmpty()) {
      throw new InvalidInboundDataException("The " + HEADER_NAME + " header is missing.");
    }

    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw new InvalidInboundDataException("The " + HEADER_NAME + " header is not valid base64.");
    }

    JsonObject json = Json.parseObject(decoded);
    if (json == null) {
      throw new InvalidInboundDataException(
          "The " + HEADER_NAME + " header doesn't contain a UTF-8 JSON object.");
    }
    if (!"1.0".equals(Json.string(json, "version"))) {
      throw new InvalidInboundDataException(
          "The " + HEADER_NAME + " header doesn't have the supported version 1.0.");
    }

    JsonObject userContext = Json.object(json, "userContext");
    if (userContext == null) {
      throw invalidField("userContext");
    }

    String accessToken = Json.string(json, "accessToken");
    if (!Json.isAbsent(json, "accessToken") && (accessToken == null || accessToken.isEmpty())) {
      throw invalidField("accessToken");
    }
    String namespace = Json.string(json, "namespace");
    if (!Json.isAbsent(json, "namespace") && namespace == null) {
      throw invalidField("namespace");
    }

    return new InvocationAppLinkContext(
        new Org(
            requiredString(json, "orgId", ""),
            requiredString(json, "orgDomainUrl", ""),
            requiredString(json, "apiVersion", ""),
            Optional.empty()),
        new User(
            requiredString(userContext, "userId", "userContext."),
            requiredString(userContext, "username", "userContext.")),
        requiredString(json, "requestId", ""),
        Optional.ofNullable(namespace).filter(s -> !s.isEmpty()),
        Optional.ofNullable(accessToken).map(token -> new Credentials(token, Optional.empty())));
  }

  private static String requiredString(JsonObject object, String name, String prefix) {
    String value = Json.string(object, name);
    if (value == null || value.isEmpty()) {
      throw invalidField(prefix + name);
    }
    return value;
  }

  private static InvalidInboundDataException invalidField(String field) {
    return new InvalidInboundDataException(
        "The " + HEADER_NAME + " header has no valid \"" + field + "\" field.");
  }
}
