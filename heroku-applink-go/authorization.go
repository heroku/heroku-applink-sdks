package applink

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"
)

const maxExpiration = 253402300799999

var absoluteURL = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9+.-]*://`)

type resolvedAttachment struct {
	apiURL string
	token  string
	appID  string
}

func (r resolvedAttachment) String() string {
	return fmt.Sprintf("{apiURL:%s token:[REDACTED] appID:%s}", r.apiURL, r.appID)
}

func (r resolvedAttachment) GoString() string {
	return r.String()
}

func resolveAttachment(env map[string]string, attachment *string) (resolvedAttachment, error) {
	apiURLVar, tokenVar := "HEROKU_APPLINK_API_URL", "HEROKU_APPLINK_TOKEN"
	switch {
	case attachment == nil:
	case *attachment == "":
		return resolvedAttachment{}, configurationError("attachment must not be empty")
	case absoluteURL.MatchString(*attachment):
		wanted := normalizeURL(*attachment)
		var matches []string
		for name, value := range env {
			if strings.HasSuffix(name, "_API_URL") && normalizeURL(value) == wanted {
				matches = append(matches, name)
			}
		}
		slices.Sort(matches)
		switch len(matches) {
		case 0:
			return resolvedAttachment{}, configurationError(
				"no config var ending in _API_URL matches attachment URL %s", *attachment)
		case 1:
		default:
			return resolvedAttachment{}, configurationError(
				"several config vars match attachment URL %s: %s", *attachment, strings.Join(matches, ", "))
		}
		apiURLVar = matches[0]
		tokenVar = strings.TrimSuffix(apiURLVar, "API_URL") + "TOKEN"
	default:
		name := asciiUpper(*attachment)
		if env[name+"_API_URL"] != "" && env[name+"_TOKEN"] != "" {
			apiURLVar, tokenVar = name+"_API_URL", name+"_TOKEN"
		} else {
			apiURLVar, tokenVar = "HEROKU_APPLINK_"+name+"_API_URL", "HEROKU_APPLINK_"+name+"_TOKEN"
		}
	}

	var resolved resolvedAttachment
	for _, v := range []struct {
		name  string
		value *string
	}{{apiURLVar, &resolved.apiURL}, {tokenVar, &resolved.token}, {"HEROKU_APP_ID", &resolved.appID}} {
		*v.value = env[v.name]
		if *v.value == "" {
			return resolvedAttachment{}, configurationError("config var %s is missing or empty", v.name)
		}
	}
	if !isHTTPSURL(resolved.apiURL) {
		return resolvedAttachment{}, configurationError("config var %s must contain an https URL", apiURLVar)
	}
	resolved.apiURL = strings.TrimSuffix(resolved.apiURL, "/")
	return resolved, nil
}

// isHTTPSURL checks the scheme with ASCII rules, because strings.EqualFold would match non-ASCII
// letters such as "ſ".
func isHTTPSURL(value string) bool {
	const scheme = "https://"
	return len(value) >= len(scheme) && asciiLower(value[:len(scheme)]) == scheme
}

func normalizeURL(url string) string {
	return strings.TrimSuffix(asciiLower(url), "/")
}

func asciiUpper(value string) string {
	return strings.Map(func(r rune) rune {
		if 'a' <= r && r <= 'z' {
			return r - 'a' + 'A'
		}
		return r
	}, value)
}

func asciiLower(value string) string {
	return strings.Map(func(r rune) rune {
		if 'A' <= r && r <= 'Z' {
			return r - 'A' + 'a'
		}
		return r
	}, value)
}

func authorizationPath(developerName string) string {
	const hex = "0123456789ABCDEF"
	var path strings.Builder
	path.WriteString("/authorizations/")
	for _, b := range []byte(developerName) {
		if 'A' <= b && b <= 'Z' || 'a' <= b && b <= 'z' || '0' <= b && b <= '9' || strings.IndexByte("-._~", b) >= 0 {
			path.WriteByte(b)
		} else {
			path.Write([]byte{'%', hex[b>>4], hex[b&15]})
		}
	}
	return path.String()
}

func parseAuthorizationResponse(body []byte) (*AuthorizationAppLinkContext, error) {
	data := parseJSONObject(body)
	if data == nil {
		return nil, unexpectedResponse("response from AppLink API is not a JSON object")
	}
	status, _ := data["status"].(string)
	switch AuthorizationStatus(status) {
	case AuthorizationStatusAuthorized:
	case AuthorizationStatusAuthorizing, AuthorizationStatusDisconnecting, AuthorizationStatusDisconnected,
		AuthorizationStatusFailed:
		return nil, &UnusableAuthorizationError{
			message: fmt.Sprintf("authorization has status %s instead of authorized", status),
			Status:  AuthorizationStatus(status),
		}
	default:
		return nil, invalidResponseField("status")
	}

	org, ok := data["org"].(jsonObject)
	if !ok {
		return nil, invalidResponseField("org")
	}
	userAuth, ok := org["user_auth"].(jsonObject)
	if !ok {
		return nil, invalidResponseField("org.user_auth")
	}
	orgType, _ := org["type"].(string)
	if OrgType(orgType) != OrgTypeSalesforceOrg && OrgType(orgType) != OrgTypeDataCloudOrg {
		return nil, invalidResponseField("org.type")
	}

	fields := fieldReader{invalid: invalidResponseField}
	context := &AuthorizationAppLinkContext{
		AuthorizationMetadata: AuthorizationMetadata{
			ID:             fields.get(data, "", "id"),
			DeveloperName:  fields.get(org, "org.", "developer_name"),
			CreatedAt:      fields.timestamp(data, "created_at"),
			CreatedBy:      fields.get(data, "", "created_by"),
			CreatedViaApp:  fields.optional(data, "created_via_app"),
			LastModifiedAt: fields.timestamp(data, "last_modified_at"),
			LastModifiedBy: fields.optional(data, "last_modified_by"),
			RedirectURI:    fields.optional(data, "redirect_uri"),
		},
		AppLinkContext: AppLinkContext{
			Org: Org{
				ID:         fields.get(org, "org.", "id"),
				DomainURL:  fields.get(org, "org.", "instance_url"),
				APIVersion: fields.get(org, "org.", "api_version"),
				Type:       OrgType(orgType),
			},
			User: User{
				ID:       fields.get(userAuth, "org.user_auth.", "user_id"),
				Username: fields.get(userAuth, "org.user_auth.", "username"),
			},
		},
		Credentials: Credentials{
			AccessToken: fields.get(userAuth, "org.user_auth.", "access_token"),
			Expiration:  fields.expiration(userAuth),
		},
	}
	if fields.err != nil {
		return nil, fields.err
	}
	return context, nil
}

func (r *fieldReader) optional(object jsonObject, field string) string {
	value, ok := object[field].(string)
	if !ok && object[field] != nil {
		r.fail(field)
	}
	return value
}

func (r *fieldReader) timestamp(object jsonObject, field string) time.Time {
	value := r.get(object, "", field)
	if value == "" {
		return time.Time{}
	}
	parsed, err := time.Parse(time.RFC3339Nano, value)
	if err != nil {
		r.fail(field)
	}
	return parsed.UTC()
}

func (r *fieldReader) expiration(userAuth jsonObject) time.Time {
	value, present := userAuth["expiration"]
	if !present || value == nil {
		return time.Time{}
	}
	milliseconds, ok := jsonInt64(value)
	if !ok || milliseconds < 0 || milliseconds > maxExpiration {
		r.fail("org.user_auth.expiration")
		return time.Time{}
	}
	if milliseconds == 0 {
		return time.Time{}
	}
	return time.UnixMilli(milliseconds).UTC()
}

func configurationError(format string, args ...any) error {
	return &ConfigurationError{message: fmt.Sprintf(format, args...)}
}

func unexpectedResponse(message string) error {
	return &UnexpectedResponseError{message: message}
}

func invalidResponseField(field string) error {
	return unexpectedResponse(fmt.Sprintf("response from AppLink API has no valid %q field", field))
}
