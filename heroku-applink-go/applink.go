// Package applink is the Heroku AppLink SDK for Go.
//
// Use [ParseInvocationContext] to get the context of an invocation from Salesforce, and
// [GetAuthorization] to look up a stored authorization. Apps call Salesforce APIs with the
// Salesforce client of their choice, using the org and credentials the SDK returns.
//
// See https://devcenter.heroku.com/articles/heroku-applink
package applink

import (
	"context"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

// Version is the version of the SDK.
const Version = "0.1.0"

const userAgent = "heroku-applink-go/" + Version

// An Option configures [GetAuthorization].
type Option func(*options)

type options struct {
	attachment  *string
	env         map[string]string
	transport   http.RoundTripper
	timeout     time.Duration
	retryDelays []time.Duration
}

// WithAttachment selects the AppLink add-on attachment to use, by name (such as
// "HEROKU_APPLINK_PURPLE"), color (such as "purple"), or API URL. Without it, the SDK uses the
// default attachment HEROKU_APPLINK.
func WithAttachment(attachment string) Option {
	return func(o *options) {
		o.attachment = &attachment
	}
}

// GetAuthorization looks up a stored authorization through the AppLink API.
//
// Every call sends a request to the AppLink API. Apps can keep the returned context and reuse it
// until Credentials.Expiration.
//
// GetAuthorization returns an [*ConfigurationError] if developerName or the attachment is empty,
// or the config vars are missing or invalid, a [*TransportError] if the request to the AppLink API
// didn't complete, an [*HTTPError] if the AppLink API responded with a status other than 2xx, an
// [*UnusableAuthorizationError] if the authorization isn't authorized, and an
// [*UnexpectedResponseError] if the response doesn't have the expected content.
//
// See https://devcenter.heroku.com/articles/heroku-applink-api
func GetAuthorization(ctx context.Context, developerName string, opts ...Option) (*AuthorizationAppLinkContext, error) {
	o := &options{
		timeout:     5 * time.Second,
		retryDelays: []time.Duration{250 * time.Millisecond, 500 * time.Millisecond, time.Second},
	}
	for _, opt := range opts {
		opt(o)
	}
	if o.env == nil {
		o.env = environment()
	}

	if developerName == "" {
		return nil, configurationError("developer name must not be empty")
	}
	resolved, err := resolveAttachment(o.env, o.attachment)
	if err != nil {
		return nil, err
	}
	apiURL, err := url.Parse(resolved.apiURL)
	if err != nil || apiURL.Host == "" {
		return nil, configurationError("invalid AppLink API URL")
	}
	requestURL, err := url.Parse("https://" + apiURL.Host + apiURL.EscapedPath() + authorizationPath(developerName))
	if err != nil {
		return nil, configurationError("invalid AppLink API URL")
	}

	response, err := getWithRetries(ctx, o, requestURL, http.Header{
		"Authorization": {"Bearer " + resolved.token},
		"X-App-UUID":    {resolved.appID},
		"Accept":        {"application/json"},
		"User-Agent":    {userAgent},
	})
	if err != nil {
		return nil, err
	}
	if response.status < 200 || response.status > 299 {
		return nil, &HTTPError{
			message: fmt.Sprintf("unexpected HTTP status %d from AppLink API", response.status),
			Status:  response.status,
			Body:    decodeErrorBody(response.body),
		}
	}
	if response.bodyTooLarge {
		return nil, unexpectedResponse("response from AppLink API exceeds 1 MiB")
	}
	return parseAuthorizationResponse(response.body)
}

func environment() map[string]string {
	env := map[string]string{}
	for _, entry := range os.Environ() {
		if name, value, ok := strings.Cut(entry, "="); ok {
			env[name] = value
		}
	}
	return env
}
