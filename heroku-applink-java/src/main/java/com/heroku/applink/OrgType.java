package com.heroku.applink;

/** The type of a Salesforce org, as returned by the AppLink API. */
public enum OrgType {
  /** A Salesforce org ({@code SalesforceOrg}). */
  SALESFORCE_ORG("SalesforceOrg"),
  /** A Data 360 org ({@code DataCloudOrg}). */
  DATA_CLOUD_ORG("DataCloudOrg");

  private final String value;

  OrgType(String value) {
    this.value = value;
  }

  static OrgType fromValue(String value) {
    for (OrgType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    return null;
  }
}
