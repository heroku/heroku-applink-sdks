package applink

import (
	"encoding/base64"
	"fmt"
)

const headerName = "x-client-context"

// ParseInvocationContext parses the context of an invocation from Salesforce from the
// x-client-context header of an inbound request.
//
// getHeader returns the value of the inbound request's header with the given name, or an empty
// string if the request doesn't have it. The SDK always passes header names in lower case.
// [net/http.Header.Get] fits:
//
//	context, err := applink.ParseInvocationContext(r.Header.Get)
//
// The SDK trusts the header, because AppLink's Service Mesh authenticates requests before they
// reach the app. If the header is missing or malformed, ParseInvocationContext returns an
// [*InvalidInboundDataError].
//
// See https://devcenter.heroku.com/articles/invoking-heroku-applink-apps
func ParseInvocationContext(getHeader func(name string) string) (*InvocationAppLinkContext, error) {
	header := getHeader(headerName)
	if header == "" {
		return nil, invalidInboundData("%s header is missing", headerName)
	}
	decoded, err := base64.StdEncoding.DecodeString(header)
	if err != nil {
		return nil, invalidInboundData("%s header is not valid base64", headerName)
	}

	data := parseJSONObject(decoded)
	if data == nil {
		return nil, invalidInboundData("%s header doesn't contain a UTF-8 JSON object", headerName)
	}
	if version, _ := data["version"].(string); version != "1.0" {
		return nil, invalidInboundData("%s header doesn't have the supported version 1.0", headerName)
	}

	userContext, ok := data["userContext"].(jsonObject)
	if !ok {
		return nil, invalidInvocationField("userContext")
	}
	var credentials *Credentials
	if value, present := data["accessToken"]; present && value != nil {
		accessToken, ok := nonEmptyString(value)
		if !ok {
			return nil, invalidInvocationField("accessToken")
		}
		credentials = &Credentials{AccessToken: accessToken}
	}
	namespace, ok := data["namespace"].(string)
	if !ok && data["namespace"] != nil {
		return nil, invalidInvocationField("namespace")
	}

	fields := fieldReader{invalid: invalidInvocationField}
	context := &InvocationAppLinkContext{
		RequestID: fields.get(data, "", "requestId"),
		AppLinkContext: AppLinkContext{
			Org: Org{
				ID:         fields.get(data, "", "orgId"),
				DomainURL:  fields.get(data, "", "orgDomainUrl"),
				APIVersion: fields.get(data, "", "apiVersion"),
			},
			User: User{
				ID:       fields.get(userContext, "userContext.", "userId"),
				Username: fields.get(userContext, "userContext.", "username"),
			},
		},
		Namespace:   namespace,
		Credentials: credentials,
	}
	if fields.err != nil {
		return nil, fields.err
	}
	return context, nil
}

func invalidInboundData(format string, args ...any) error {
	return &InvalidInboundDataError{message: fmt.Sprintf(format, args...)}
}

func invalidInvocationField(field string) error {
	return invalidInboundData("%s header has no valid %q field", headerName, field)
}
