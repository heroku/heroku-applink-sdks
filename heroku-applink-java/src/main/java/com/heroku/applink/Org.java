package com.heroku.applink;

import java.util.Objects;
import java.util.Optional;

/**
 * A Salesforce org that the app works with.
 *
 * @param id the ID of the org
 * @param domainUrl the org's My Domain URL; pass it to a Salesforce client as the instance URL
 * @param apiVersion the org's Salesforce API version, such as {@code 62.0}
 * @param type the type of the org; present in an {@link AuthorizationAppLinkContext}, empty in an
 *     {@link InvocationAppLinkContext}
 */
public record Org(String id, String domainUrl, String apiVersion, Optional<OrgType> type) {
  /**
   * Creates an org.
   *
   * @param id the ID of the org
   * @param domainUrl the org's My Domain URL
   * @param apiVersion the org's Salesforce API version
   * @param type the type of the org
   */
  public Org {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(domainUrl, "domainUrl");
    Objects.requireNonNull(apiVersion, "apiVersion");
    Objects.requireNonNull(type, "type");
  }
}
