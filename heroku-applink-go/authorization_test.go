package applink

import (
	"errors"
	"strings"
	"testing"
)

func TestAuthorizationResponseVectors(t *testing.T) {
	for _, vector := range loadVectors(t, "authorization-response.json") {
		t.Run(str(vector["name"]), func(t *testing.T) {
			context, err := parseAuthorizationResponse([]byte(str(vector["body"])))

			expected := object(vector["expected"])
			if expected == nil || (err != nil && vector["error"] != nil) {
				if vector["error"] == "unusable_authorization" {
					var unusable *UnusableAuthorizationError
					if !errors.As(err, &unusable) {
						t.Fatalf("expected an UnusableAuthorizationError, got %v", err)
					}
					assertEqual(t, "Status", AuthorizationStatus(str(vector["status"])), unusable.Status)
					return
				}
				var unexpected *UnexpectedResponseError
				if !errors.As(err, &unexpected) {
					t.Fatalf("expected an UnexpectedResponseError, got %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}

			metadata := object(expected["authorizationMetadata"])
			org, user, credentials := object(expected["org"]), object(expected["user"]), object(expected["credentials"])
			actual := context.AuthorizationMetadata
			assertEqual(t, "ID", str(metadata["id"]), actual.ID)
			assertEqual(t, "DeveloperName", str(metadata["developerName"]), actual.DeveloperName)
			assertInstant(t, "CreatedAt", instant(t, metadata["createdAt"]), actual.CreatedAt)
			assertEqual(t, "CreatedBy", str(metadata["createdBy"]), actual.CreatedBy)
			assertEqual(t, "CreatedViaApp", str(metadata["createdViaApp"]), actual.CreatedViaApp)
			assertInstant(t, "LastModifiedAt", instant(t, metadata["lastModifiedAt"]), actual.LastModifiedAt)
			assertEqual(t, "LastModifiedBy", str(metadata["lastModifiedBy"]), actual.LastModifiedBy)
			assertEqual(t, "RedirectURI", str(metadata["redirectUri"]), actual.RedirectURI)
			assertEqual(t, "Org.ID", str(org["id"]), context.Org.ID)
			assertEqual(t, "Org.DomainURL", str(org["domainUrl"]), context.Org.DomainURL)
			assertEqual(t, "Org.APIVersion", str(org["apiVersion"]), context.Org.APIVersion)
			assertEqual(t, "Org.Type", OrgType(str(org["type"])), context.Org.Type)
			assertEqual(t, "User.ID", str(user["id"]), context.User.ID)
			assertEqual(t, "User.Username", str(user["username"]), context.User.Username)
			assertEqual(t, "AccessToken", str(credentials["accessToken"]), context.Credentials.AccessToken)
			if credentials["expiration"] == nil {
				if !context.Credentials.Expiration.IsZero() {
					t.Errorf("expected no expiration, got %v", context.Credentials.Expiration)
				}
			} else {
				assertInstant(t, "Expiration", instant(t, credentials["expiration"]), context.Credentials.Expiration)
			}
		})
	}
}

func TestAuthorizationResponseErrorsDontLeakTheBody(t *testing.T) {
	_, err := parseAuthorizationResponse([]byte(`{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}`))
	if err == nil || strings.Contains(err.Error(), "secret-token") {
		t.Errorf("unexpected error %v", err)
	}
}

func TestAttachmentResolutionVectors(t *testing.T) {
	for _, vector := range loadVectors(t, "attachment-resolution.json") {
		t.Run(str(vector["name"]), func(t *testing.T) {
			env := map[string]string{}
			for name, value := range object(vector["env"]) {
				env[name] = str(value)
			}
			var attachment *string
			if value, ok := vector["attachment"].(string); ok {
				attachment = &value
			}
			resolved, err := resolveAttachment(env, attachment)

			expected := object(vector["expected"])
			if expected == nil {
				var configuration *ConfigurationError
				if !errors.As(err, &configuration) {
					t.Fatalf("expected a ConfigurationError, got %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			assertEqual(t, "apiURL", str(expected["apiUrl"]), resolved.apiURL)
			assertEqual(t, "token", str(expected["token"]), resolved.token)
			assertEqual(t, "appID", str(expected["appId"]), resolved.appID)
		})
	}
}

func TestRequestPathVectors(t *testing.T) {
	for _, vector := range loadVectors(t, "request-path.json") {
		t.Run(str(vector["developerName"]), func(t *testing.T) {
			assertEqual(t, "path", str(vector["path"]), authorizationPath(str(vector["developerName"])))
		})
	}
}

func TestDecodeErrorBodyReplacesMaximalSubparts(t *testing.T) {
	assertEqual(t, "body", "a�b�x���", decodeErrorBody([]byte("a\xffb\xf0\x9f\x98x\xed\xa0\x80")))
}
