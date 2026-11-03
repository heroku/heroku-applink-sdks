package com.heroku.applink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AuthorizationTest {
  static List<Arguments> responseVectors() {
    return Vectors.arguments("authorization-response.json", "name");
  }

  static List<Arguments> attachmentVectors() {
    return Vectors.arguments("attachment-resolution.json", "name");
  }

  static List<Arguments> pathVectors() {
    return Vectors.arguments("request-path.json", "developerName");
  }

  @ParameterizedTest
  @MethodSource("responseVectors")
  void responseVectors(JsonObject vector) {
    byte[] body = Vectors.string(vector, "body").getBytes(StandardCharsets.UTF_8);
    JsonObject expected = vector.getAsJsonObject("expected");
    String error = vector.has("error") ? Vectors.string(vector, "error") : null;
    AuthorizationAppLinkContext context;
    if (expected != null && error != null) {
      try {
        context = AuthorizationResponseParser.parse(body);
      } catch (UnusableAuthorizationException e) {
        assertEquals("unusable_authorization", error);
        assertEquals(AuthorizationStatus.fromValue(Vectors.string(vector, "status")), e.status());
        return;
      } catch (UnexpectedResponseException e) {
        assertEquals("unexpected_response", error);
        assertNull(e.getCause());
        return;
      }
    } else if ("unusable_authorization".equals(error)) {
      UnusableAuthorizationException e =
          assertThrows(
              UnusableAuthorizationException.class, () -> AuthorizationResponseParser.parse(body));
      assertEquals(AuthorizationStatus.fromValue(Vectors.string(vector, "status")), e.status());
      return;
    } else if (expected == null) {
      UnexpectedResponseException e =
          assertThrows(
              UnexpectedResponseException.class, () -> AuthorizationResponseParser.parse(body));
      assertNull(e.getCause());
      return;
    } else {
      context = AuthorizationResponseParser.parse(body);
    }

    AuthorizationMetadata metadata = context.authorizationMetadata();
    JsonObject expectedMetadata = expected.getAsJsonObject("authorizationMetadata");
    JsonObject org = expected.getAsJsonObject("org");
    JsonObject user = expected.getAsJsonObject("user");
    JsonObject credentials = expected.getAsJsonObject("credentials");
    assertEquals(Vectors.string(expectedMetadata, "id"), metadata.id());
    assertEquals(Vectors.string(expectedMetadata, "developerName"), metadata.developerName());
    assertEquals(
        Instant.parse(Vectors.string(expectedMetadata, "createdAt")), metadata.createdAt());
    assertEquals(Vectors.string(expectedMetadata, "createdBy"), metadata.createdBy());
    assertEquals(
        Vectors.optionalString(expectedMetadata, "createdViaApp"), metadata.createdViaApp());
    assertEquals(
        Instant.parse(Vectors.string(expectedMetadata, "lastModifiedAt")),
        metadata.lastModifiedAt());
    assertEquals(
        Vectors.optionalString(expectedMetadata, "lastModifiedBy"), metadata.lastModifiedBy());
    assertEquals(Vectors.optionalString(expectedMetadata, "redirectUri"), metadata.redirectUri());
    assertEquals(Vectors.string(org, "id"), context.org().id());
    assertEquals(Vectors.string(org, "domainUrl"), context.org().domainUrl());
    assertEquals(Vectors.string(org, "apiVersion"), context.org().apiVersion());
    assertEquals(
        OrgType.fromValue(Vectors.string(org, "type")), context.org().type().orElseThrow());
    assertEquals(Vectors.string(user, "id"), context.user().id());
    assertEquals(Vectors.string(user, "username"), context.user().username());
    assertEquals(Vectors.string(credentials, "accessToken"), context.credentials().accessToken());
    assertEquals(
        Vectors.optionalInstant(credentials, "expiration"), context.credentials().expiration());
  }

  @Test
  void responseErrorsDontLeakTheBody() {
    byte[] body =
        "{\"status\":\"authorized\",\"org\":{\"user_auth\":{\"access_token\":\"secret-token\"}}}"
            .getBytes(StandardCharsets.UTF_8);
    UnexpectedResponseException e =
        assertThrows(
            UnexpectedResponseException.class, () -> AuthorizationResponseParser.parse(body));
    assertFalse(e.getMessage().contains("secret-token"));
  }

  @ParameterizedTest
  @MethodSource("attachmentVectors")
  void attachmentVectors(JsonObject vector) {
    Map<String, String> env = Vectors.stringMap(vector.getAsJsonObject("env"));
    String attachment = Vectors.string(vector, "attachment");
    JsonObject expected = vector.getAsJsonObject("expected");
    if (expected == null) {
      assertThrows(ConfigurationException.class, () -> AttachmentResolver.resolve(env, attachment));
      return;
    }
    AttachmentResolver.ResolvedAttachment resolved = AttachmentResolver.resolve(env, attachment);
    assertEquals(Vectors.string(expected, "apiUrl"), resolved.apiUrl());
    assertEquals(Vectors.string(expected, "token"), resolved.token());
    assertEquals(Vectors.string(expected, "appId"), resolved.appId());
  }

  @ParameterizedTest
  @MethodSource("pathVectors")
  void pathVectors(JsonObject vector) {
    assertEquals(
        Vectors.string(vector, "path"),
        AuthorizationResponseParser.path(Vectors.string(vector, "developerName")));
  }
}
