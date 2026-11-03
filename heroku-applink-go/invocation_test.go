package applink

import (
	"encoding/base64"
	"errors"
	"strings"
	"testing"
)

func TestInvocationContextVectors(t *testing.T) {
	for _, vector := range loadVectors(t, "invocation-context.json") {
		t.Run(str(vector["name"]), func(t *testing.T) {
			getHeader := func(name string) string {
				if name == "x-client-context" {
					return str(vector["header"])
				}
				return ""
			}
			context, err := ParseInvocationContext(getHeader)

			expected := object(vector["expected"])
			if expected == nil || (err != nil && vector["error"] != nil) {
				var invalid *InvalidInboundDataError
				if !errors.As(err, &invalid) {
					t.Fatalf("expected an InvalidInboundDataError, got %v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			org, user := object(expected["org"]), object(expected["user"])
			assertEqual(t, "RequestID", str(expected["requestId"]), context.RequestID)
			assertEqual(t, "Namespace", str(expected["namespace"]), context.Namespace)
			assertEqual(t, "Org.ID", str(org["id"]), context.Org.ID)
			assertEqual(t, "Org.DomainURL", str(org["domainUrl"]), context.Org.DomainURL)
			assertEqual(t, "Org.APIVersion", str(org["apiVersion"]), context.Org.APIVersion)
			assertEqual(t, "Org.Type", "", context.Org.Type)
			assertEqual(t, "User.ID", str(user["id"]), context.User.ID)
			assertEqual(t, "User.Username", str(user["username"]), context.User.Username)
			if expected["credentials"] == nil {
				if context.Credentials != nil {
					t.Errorf("expected no credentials, got %v", context.Credentials)
				}
			} else {
				if context.Credentials == nil {
					t.Fatal("expected credentials")
				}
				assertEqual(t, "AccessToken", str(object(expected["credentials"])["accessToken"]), context.Credentials.AccessToken)
				if !context.Credentials.Expiration.IsZero() {
					t.Errorf("expected no expiration, got %v", context.Credentials.Expiration)
				}
			}
		})
	}
}

func TestParseInvocationContextAsksForTheHeaderInLowerCase(t *testing.T) {
	var names []string
	_, err := ParseInvocationContext(func(name string) string {
		names = append(names, name)
		return ""
	})
	if err == nil {
		t.Fatal("expected an error")
	}
	if len(names) != 1 || names[0] != "x-client-context" {
		t.Errorf("expected [x-client-context], got %v", names)
	}
}

func TestParseInvocationContextErrorsDontLeakTheHeader(t *testing.T) {
	header := base64.StdEncoding.EncodeToString([]byte(`{"accessToken":"secret-token","version":"1.0"`))
	_, err := ParseInvocationContext(func(string) string { return header })
	if err == nil || strings.Contains(err.Error(), "secret-token") {
		t.Errorf("unexpected error %v", err)
	}
	if errors.Unwrap(err) != nil {
		t.Errorf("expected no wrapped error, got %v", errors.Unwrap(err))
	}
	if !errors.Is(err, ErrAppLink) {
		t.Error("expected the error to match ErrAppLink")
	}
}
