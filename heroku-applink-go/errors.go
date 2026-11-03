package applink

import (
	"errors"
)

// ErrAppLink matches every error the SDK returns with [errors.Is].
var ErrAppLink = errors.New("applink")

// InvalidInboundDataError means data sent to the app is missing or can't be parsed, for example a
// missing or malformed x-client-context header.
type InvalidInboundDataError struct {
	message string
}

func (e *InvalidInboundDataError) Error() string        { return e.message }
func (e *InvalidInboundDataError) Is(target error) bool { return target == ErrAppLink }

// ConfigurationError means an authorization lookup can't start, because its input or the app's
// config vars are missing or invalid. It is returned before any request is sent.
type ConfigurationError struct {
	message string
}

func (e *ConfigurationError) Error() string        { return e.message }
func (e *ConfigurationError) Is(target error) bool { return target == ErrAppLink }

// TransportError means a request to the AppLink API didn't complete, for example because the
// connection failed or timed out. If the caller's context ended the request, the error wraps the
// context's error.
type TransportError struct {
	message string
	err     error
}

func (e *TransportError) Error() string        { return e.message }
func (e *TransportError) Is(target error) bool { return target == ErrAppLink }
func (e *TransportError) Unwrap() error        { return e.err }

// HTTPError means the AppLink API responded with a status other than 2xx.
type HTTPError struct {
	message string
	// Status is the HTTP status of the response, such as 404 for an unknown authorization.
	Status int
	// Body is the response body as UTF-8 text, with invalid bytes replaced. At most 1 MiB is read.
	Body string
}

func (e *HTTPError) Error() string        { return e.message }
func (e *HTTPError) Is(target error) bool { return target == ErrAppLink }

// UnusableAuthorizationError means the AppLink API returned the authorization, but it isn't
// [AuthorizationStatusAuthorized], for example because it is still being authorized or was
// disconnected.
type UnusableAuthorizationError struct {
	message string
	// Status is the status of the authorization.
	Status AuthorizationStatus
}

func (e *UnusableAuthorizationError) Error() string        { return e.message }
func (e *UnusableAuthorizationError) Is(target error) bool { return target == ErrAppLink }

// UnexpectedResponseError means the AppLink API responded with a 2xx status, but unexpected
// content, for example a response with a missing required field.
type UnexpectedResponseError struct {
	message string
}

func (e *UnexpectedResponseError) Error() string        { return e.message }
func (e *UnexpectedResponseError) Is(target error) bool { return target == ErrAppLink }
