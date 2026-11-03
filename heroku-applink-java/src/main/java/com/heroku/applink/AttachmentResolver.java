package com.heroku.applink;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

final class AttachmentResolver {
  private static final Pattern ABSOLUTE_URL = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://");
  private static final Pattern HTTPS_URL = Pattern.compile("^https://", Pattern.CASE_INSENSITIVE);

  record ResolvedAttachment(String apiUrl, String token, String appId) {
    @Override
    public String toString() {
      return "ResolvedAttachment[apiUrl=" + apiUrl + ", token=[REDACTED], appId=" + appId + "]";
    }
  }

  private AttachmentResolver() {}

  static ResolvedAttachment resolve(Map<String, String> env, String attachment) {
    String apiUrlVar;
    String tokenVar;

    if (attachment == null) {
      apiUrlVar = "HEROKU_APPLINK_API_URL";
      tokenVar = "HEROKU_APPLINK_TOKEN";
    } else if (attachment.isEmpty()) {
      throw new ConfigurationException("The attachment must not be empty.");
    } else if (ABSOLUTE_URL.matcher(attachment).find()) {
      String wanted = normalizeUrl(attachment);
      List<String> matches =
          env.entrySet().stream()
              .filter(e -> e.getKey().endsWith("_API_URL") && e.getValue() != null)
              .filter(e -> normalizeUrl(e.getValue()).equals(wanted))
              .map(Map.Entry::getKey)
              .sorted()
              .toList();
      if (matches.isEmpty()) {
        throw new ConfigurationException(
            "No config var ending in _API_URL matches the attachment URL " + attachment + ".");
      }
      if (matches.size() > 1) {
        throw new ConfigurationException(
            "Several config vars match the attachment URL "
                + attachment
                + ": "
                + String.join(", ", matches)
                + ".");
      }
      apiUrlVar = matches.getFirst();
      tokenVar = apiUrlVar.substring(0, apiUrlVar.length() - "API_URL".length()) + "TOKEN";
    } else {
      String name = asciiUpperCase(attachment);
      if (isSet(env, name + "_API_URL") && isSet(env, name + "_TOKEN")) {
        apiUrlVar = name + "_API_URL";
        tokenVar = name + "_TOKEN";
      } else {
        apiUrlVar = "HEROKU_APPLINK_" + name + "_API_URL";
        tokenVar = "HEROKU_APPLINK_" + name + "_TOKEN";
      }
    }

    String apiUrl = requiredConfigVar(env, apiUrlVar);
    String token = requiredConfigVar(env, tokenVar);
    String appId = requiredConfigVar(env, "HEROKU_APP_ID");
    if (!HTTPS_URL.matcher(apiUrl).find()) {
      throw new ConfigurationException(
          "The config var " + apiUrlVar + " must contain an https URL.");
    }
    return new ResolvedAttachment(removeTrailingSlash(apiUrl), token, appId);
  }

  private static boolean isSet(Map<String, String> env, String name) {
    String value = env.get(name);
    return value != null && !value.isEmpty();
  }

  private static String requiredConfigVar(Map<String, String> env, String name) {
    if (!isSet(env, name)) {
      throw new ConfigurationException("The config var " + name + " is missing or empty.");
    }
    return env.get(name);
  }

  private static String normalizeUrl(String url) {
    return removeTrailingSlash(asciiLowerCase(url));
  }

  private static String asciiLowerCase(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      result.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
    }
    return result.toString();
  }

  private static String asciiUpperCase(String value) {
    StringBuilder result = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      result.append(c >= 'a' && c <= 'z' ? (char) (c - ('a' - 'A')) : c);
    }
    return result.toString();
  }

  private static String removeTrailingSlash(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
