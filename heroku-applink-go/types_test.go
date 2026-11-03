package applink

import (
	"bytes"
	"fmt"
	"log/slog"
	"os"
	"regexp"
	"strings"
	"testing"
	"time"
)

func TestFormattingRedactsTheAccessToken(t *testing.T) {
	credentials := &Credentials{AccessToken: "secret-token", Expiration: time.Date(2026, 10, 13, 9, 15, 42, 0, time.UTC)}
	invocation := InvocationAppLinkContext{
		AppLinkContext: AppLinkContext{
			Org:  Org{ID: "org", DomainURL: "https://example.tld", APIVersion: "62.0"},
			User: User{ID: "user", Username: "user@example.tld"},
		},
		RequestID:   "request",
		Credentials: credentials,
	}
	authorization := AuthorizationAppLinkContext{AppLinkContext: invocation.AppLinkContext, Credentials: *credentials}
	resolved := resolvedAttachment{apiURL: "https://example.tld", token: "secret-token", appID: "app"}

	var log bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&log, nil))
	logger.Info("context", "invocation", invocation, "authorization", authorization, "credentials", credentials)

	outputs := []string{log.String()}
	for _, format := range []string{"%v", "%+v", "%#v", "%s"} {
		for _, value := range []any{credentials, *credentials, invocation, &invocation, authorization, resolved} {
			outputs = append(outputs, fmt.Sprintf(format, value))
		}
	}
	for _, output := range outputs {
		if strings.Contains(output, "secret-token") {
			t.Errorf("output contains the access token: %s", output)
		}
	}
	if !strings.Contains(credentials.String(), "[REDACTED]") {
		t.Errorf("expected [REDACTED] in %s", credentials)
	}
}

func TestUserAgentMatchesTheChangelog(t *testing.T) {
	if !regexp.MustCompile(`^[0-9]+\.[0-9]+\.[0-9]+$`).MatchString(Version) {
		t.Errorf("invalid version %s", Version)
	}
	assertEqual(t, "userAgent", "heroku-applink-go/"+Version, userAgent)
	changelog, err := os.ReadFile("CHANGELOG.md")
	if err != nil {
		t.Fatal(err)
	}
	latest := regexp.MustCompile(`(?m)^## \[([0-9]+\.[0-9]+\.[0-9]+)\]`).FindSubmatch(changelog)
	if latest != nil && string(latest[1]) != Version {
		t.Errorf("Version is %s, but the latest release in CHANGELOG.md is %s", Version, latest[1])
	}
}
