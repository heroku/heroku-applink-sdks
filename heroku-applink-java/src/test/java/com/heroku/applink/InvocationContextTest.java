package com.heroku.applink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class InvocationContextTest {
  static List<Arguments> vectors() {
    return Vectors.arguments("invocation-context.json", "name");
  }

  @ParameterizedTest
  @MethodSource("vectors")
  void vectors(JsonObject vector) {
    String header = Vectors.string(vector, "header");
    JsonObject expected = vector.getAsJsonObject("expected");
    InvocationAppLinkContext context;
    if (expected == null) {
      InvalidInboundDataException e =
          assertThrows(
              InvalidInboundDataException.class,
              () -> AppLink.parseInvocationContext(name -> header));
      assertNull(e.getCause());
      return;
    }
    if (vector.has("error")) {
      try {
        context = AppLink.parseInvocationContext(name -> header);
      } catch (InvalidInboundDataException e) {
        assertNull(e.getCause());
        return;
      }
    } else {
      context = AppLink.parseInvocationContext(name -> header);
    }
    JsonObject org = expected.getAsJsonObject("org");
    JsonObject user = expected.getAsJsonObject("user");
    assertEquals(Vectors.string(expected, "requestId"), context.requestId());
    assertEquals(Vectors.optionalString(expected, "namespace"), context.namespace());
    assertEquals(Vectors.string(org, "id"), context.org().id());
    assertEquals(Vectors.string(org, "domainUrl"), context.org().domainUrl());
    assertEquals(Vectors.string(org, "apiVersion"), context.org().apiVersion());
    assertEquals(Optional.empty(), context.org().type());
    assertEquals(Vectors.string(user, "id"), context.user().id());
    assertEquals(Vectors.string(user, "username"), context.user().username());
    if (expected.get("credentials").isJsonNull()) {
      assertEquals(Optional.empty(), context.credentials());
    } else {
      Credentials credentials = context.credentials().orElseThrow();
      assertEquals(
          Vectors.string(expected.getAsJsonObject("credentials"), "accessToken"),
          credentials.accessToken());
      assertEquals(Optional.empty(), credentials.expiration());
    }
  }

  @Test
  void asksForTheHeaderInLowerCase() {
    List<String> names = new ArrayList<>();
    assertThrows(
        InvalidInboundDataException.class,
        () ->
            AppLink.parseInvocationContext(
                name -> {
                  names.add(name);
                  return null;
                }));
    assertEquals(List.of("x-client-context"), names);
  }

  @Test
  void errorsDontLeakTheHeader() {
    String header =
        Base64.getEncoder()
            .encodeToString(
                "{\"accessToken\":\"secret-token\",\"version\":\"1.0\""
                    .getBytes(StandardCharsets.UTF_8));
    InvalidInboundDataException e =
        assertThrows(
            InvalidInboundDataException.class,
            () -> AppLink.parseInvocationContext(name -> header));
    assertFalse(e.getMessage().contains("secret-token"));
    assertNull(e.getCause());
    assertTrue(e instanceof AppLinkException);
  }
}
