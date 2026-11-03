package applink

import (
	"fmt"
	"log/slog"
	"time"
)

// OrgType is the type of a Salesforce org, as returned by the AppLink API.
type OrgType string

const (
	// OrgTypeSalesforceOrg is a Salesforce org.
	OrgTypeSalesforceOrg OrgType = "SalesforceOrg"
	// OrgTypeDataCloudOrg is a Data 360 org.
	OrgTypeDataCloudOrg OrgType = "DataCloudOrg"
)

// AuthorizationStatus is the status of an authorization, as returned by the AppLink API.
//
// Only [AuthorizationStatusAuthorized] authorizations can be used. Apps only see the other
// statuses in an [UnusableAuthorizationError].
//
// See https://devcenter.heroku.com/articles/heroku-applink-api
type AuthorizationStatus string

const (
	// AuthorizationStatusAuthorizing means the add-on is authorizing the user.
	AuthorizationStatusAuthorizing AuthorizationStatus = "authorizing"
	// AuthorizationStatusAuthorized means the user is authorized and the authorization can be used.
	AuthorizationStatusAuthorized AuthorizationStatus = "authorized"
	// AuthorizationStatusDisconnecting means the add-on is removing the authorization.
	AuthorizationStatusDisconnecting AuthorizationStatus = "disconnecting"
	// AuthorizationStatusDisconnected means the authorization has been removed.
	AuthorizationStatusDisconnected AuthorizationStatus = "disconnected"
	// AuthorizationStatusFailed means authorizing failed with an unexpected error.
	AuthorizationStatusFailed AuthorizationStatus = "failed"
)

// Org is a Salesforce org that the app works with.
type Org struct {
	// ID is the ID of the org.
	ID string
	// DomainURL is the org's My Domain URL. Pass it to a Salesforce client as the instance URL.
	DomainURL string
	// APIVersion is the org's Salesforce API version, such as "62.0".
	APIVersion string
	// Type is the type of the org. It is set in an [AuthorizationAppLinkContext], and empty in an
	// [InvocationAppLinkContext].
	Type OrgType
}

// User is a Salesforce user.
type User struct {
	// ID is the ID of the user.
	ID string
	// Username is the username of the user.
	Username string
}

// Credentials are credentials for calling Salesforce APIs.
//
// Formatting Credentials with the fmt and log/slog packages redacts the access token, so it
// doesn't leak into logs by accident.
type Credentials struct {
	// AccessToken is the Salesforce access token. Pass it to a Salesforce client as the bearer
	// token.
	AccessToken string
	// Expiration is the instant the access token expires, in UTC. It is the zero time if AppLink
	// doesn't provide one.
	Expiration time.Time
}

// String returns the credentials with the access token redacted.
func (c Credentials) String() string {
	return fmt.Sprintf("{AccessToken:[REDACTED] Expiration:%v}", c.Expiration)
}

// GoString returns the credentials with the access token redacted.
func (c Credentials) GoString() string {
	return fmt.Sprintf("applink.Credentials{AccessToken:\"[REDACTED]\", Expiration:%#v}", c.Expiration)
}

// LogValue returns the credentials with the access token redacted.
func (c Credentials) LogValue() slog.Value {
	return slog.GroupValue(
		slog.String("AccessToken", "[REDACTED]"),
		slog.Time("Expiration", c.Expiration),
	)
}

// AppLinkContext is what every context has: the org and the user. Both
// [InvocationAppLinkContext] and [AuthorizationAppLinkContext] embed it, so code that only needs
// the org and the user can accept an AppLinkContext and work with both kinds of context.
type AppLinkContext struct {
	// Org is the org the context belongs to.
	Org Org
	// User is the user acting in the org.
	User User
}

// InvocationAppLinkContext is the context of an invocation from Salesforce, decoded from the
// x-client-context header by [ParseInvocationContext]. There is one per inbound request. It
// describes the invoking user.
type InvocationAppLinkContext struct {
	AppLinkContext
	// RequestID is the ID of the invocation.
	RequestID string
	// Namespace is the invoking org's namespace prefix. It is empty when the org has none.
	Namespace string
	// Credentials are credentials for acting as the invoking user. They are nil when the
	// invocation carries no access token.
	Credentials *Credentials
}

// AuthorizationMetadata is the metadata of an authorization, as returned by the AppLink API.
//
// See https://devcenter.heroku.com/articles/heroku-applink-api
type AuthorizationMetadata struct {
	// ID is the ID of the authorization.
	ID string
	// DeveloperName is the developer name of the authorization.
	DeveloperName string
	// CreatedAt is the instant the authorization was created, in UTC.
	CreatedAt time.Time
	// CreatedBy is who created the authorization.
	CreatedBy string
	// CreatedViaApp is the app the authorization was created through, or empty.
	CreatedViaApp string
	// LastModifiedAt is the instant the authorization was last modified, in UTC.
	LastModifiedAt time.Time
	// LastModifiedBy is who last modified the authorization, or empty if unknown.
	LastModifiedBy string
	// RedirectURI is the redirect URI of the authorization, or empty.
	RedirectURI string
}

// AuthorizationAppLinkContext is the context of a stored authorization, returned by
// [GetAuthorization]. It describes the user the authorization was made for. It is long-lived, and
// apps can keep and reuse it until Credentials.Expiration.
type AuthorizationAppLinkContext struct {
	AppLinkContext
	// AuthorizationMetadata is the metadata of the authorization.
	AuthorizationMetadata AuthorizationMetadata
	// Credentials are credentials for acting as the authorized user.
	Credentials Credentials
}
