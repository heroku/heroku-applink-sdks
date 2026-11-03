package applink

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

const mib = 1024 * 1024

type recordedRequest struct {
	method     string
	requestURI string
	header     http.Header
}

type testServer struct {
	*httptest.Server
	mu       sync.Mutex
	handlers []http.HandlerFunc
	requests []recordedRequest
}

func newTestServer(t *testing.T, handlers ...http.HandlerFunc) *testServer {
	t.Helper()
	server := &testServer{handlers: handlers}
	server.Server = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		server.mu.Lock()
		server.requests = append(server.requests, recordedRequest{r.Method, r.RequestURI, r.Header.Clone()})
		handler := server.handlers[min(len(server.requests), len(server.handlers))-1]
		server.mu.Unlock()
		handler(w, r)
	}))
	t.Cleanup(server.Close)
	return server
}

func (s *testServer) recorded() []recordedRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]recordedRequest(nil), s.requests...)
}

func respondWith(status int, body string) http.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}
}

func hang(w http.ResponseWriter, r *http.Request) {
	select {
	case <-r.Context().Done():
	case <-time.After(time.Second):
	}
}

var validBody = func() string {
	vectors, err := readVectors("authorization-response.json")
	if err != nil {
		panic(err)
	}
	return str(vectors[0]["body"])
}()

func testOptions(server *testServer, env map[string]string) Option {
	return func(o *options) {
		o.env = map[string]string{
			"HEROKU_APPLINK_API_URL": server.URL + "/addons/abc/",
			"HEROKU_APPLINK_TOKEN":   "addon-token",
			"HEROKU_APP_ID":          "app-uuid",
		}
		for name, value := range env {
			o.env[name] = value
		}
		o.transport = server.Client().Transport
		o.timeout = 500 * time.Millisecond
		o.retryDelays = []time.Duration{time.Millisecond, 2 * time.Millisecond, 3 * time.Millisecond}
	}
}

func withoutRetries(o *options) {
	o.retryDelays = nil
}

func getAuthorization(t *testing.T, server *testServer, developerName string, opts ...Option) (*AuthorizationAppLinkContext, error) {
	t.Helper()
	return GetAuthorization(context.Background(), developerName, append([]Option{testOptions(server, nil)}, opts...)...)
}

func expectError[T error](t *testing.T, err error) T {
	t.Helper()
	var target T
	if !errors.As(err, &target) {
		t.Fatalf("expected a %T, got %v", target, err)
	}
	if !errors.Is(err, ErrAppLink) {
		t.Errorf("expected %v to match ErrAppLink", err)
	}
	return target
}

func expectRequests(t *testing.T, server *testServer, count int) {
	t.Helper()
	if requests := server.recorded(); len(requests) != count {
		t.Errorf("expected %d requests, got %d", count, len(requests))
	}
}

func TestSendsTheDocumentedRequest(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	context, err := getAuthorization(t, server, "my org/x")
	if err != nil {
		t.Fatal(err)
	}

	assertEqual(t, "AccessToken", "00DJS0000000123!AQ0AQ.token", context.Credentials.AccessToken)
	expectRequests(t, server, 1)
	request := server.recorded()[0]
	assertEqual(t, "method", "GET", request.method)
	assertEqual(t, "path", "/addons/abc/authorizations/my%20org%2Fx", request.requestURI)
	assertEqual(t, "Authorization", "Bearer addon-token", request.header.Get("Authorization"))
	assertEqual(t, "X-App-UUID", "app-uuid", request.header.Get("X-App-UUID"))
	assertEqual(t, "Accept", "application/json", request.header.Get("Accept"))
	assertEqual(t, "User-Agent", userAgent, request.header.Get("User-Agent"))
}

func TestSendsDotSegmentsAsIs(t *testing.T) {
	for _, developerName := range []string{".", ".."} {
		server := newTestServer(t, respondWith(200, validBody))
		if _, err := getAuthorization(t, server, developerName); err != nil {
			t.Fatal(err)
		}
		assertEqual(t, "path", "/addons/abc/authorizations/"+developerName, server.recorded()[0].requestURI)
	}
}

func TestSendsTheAPIURLPathAsIs(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	_, err := GetAuthorization(context.Background(), "x", testOptions(server, map[string]string{
		"HEROKU_APPLINK_API_URL": server.URL + "/addons/a%2Fb",
	}))
	if err != nil {
		t.Fatal(err)
	}
	assertEqual(t, "path", "/addons/a%2Fb/authorizations/x", server.recorded()[0].requestURI)
}

func TestConfigurationErrorsSendNoRequest(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	_, err := getAuthorization(t, server, "")
	expectError[*ConfigurationError](t, err)
	_, err = getAuthorization(t, server, "x", WithAttachment(""))
	expectError[*ConfigurationError](t, err)
	_, err = GetAuthorization(context.Background(), "x", testOptions(server, map[string]string{"HEROKU_APP_ID": ""}))
	expectError[*ConfigurationError](t, err)

	expectRequests(t, server, 0)
}

func TestConfigVarsTheRequestCantBeSentWith(t *testing.T) {
	for name, value := range map[string]string{
		"HEROKU_APPLINK_TOKEN":   "secret\ntoken",
		"HEROKU_APP_ID":          "app-uuid\r\nX-Injected: secret",
		"HEROKU_APPLINK_API_URL": "https:///addons/abc",
	} {
		server := newTestServer(t, respondWith(200, validBody))
		_, err := GetAuthorization(context.Background(), "x", testOptions(server, map[string]string{name: value}))
		if !errors.Is(err, ErrAppLink) || strings.Contains(err.Error(), "secret") {
			t.Errorf("%s: unexpected error %v", name, err)
		}
		expectRequests(t, server, 0)
	}
}

func TestUsesTheSelectedAttachment(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	_, err := GetAuthorization(context.Background(), "x", testOptions(server, map[string]string{
		"HEROKU_APPLINK_PURPLE_API_URL": server.URL + "/addons/purple",
		"HEROKU_APPLINK_PURPLE_TOKEN":   "purple-token",
	}), WithAttachment("purple"))
	if err != nil {
		t.Fatal(err)
	}
	request := server.recorded()[0]
	assertEqual(t, "path", "/addons/purple/authorizations/x", request.requestURI)
	assertEqual(t, "Authorization", "Bearer purple-token", request.header.Get("Authorization"))
}

func TestReportsStatusAndBody(t *testing.T) {
	server := newTestServer(t, respondWith(404, `{"message":"not found"}`))
	_, err := getAuthorization(t, server, "x")
	httpError := expectError[*HTTPError](t, err)

	assertEqual(t, "Status", 404, httpError.Status)
	assertEqual(t, "Body", `{"message":"not found"}`, httpError.Body)
	assertEqual(t, "message", "unexpected HTTP status 404 from AppLink API", httpError.Error())
	expectRequests(t, server, 1)
}

func TestReplacesInvalidUTF8InErrorBodies(t *testing.T) {
	server := newTestServer(t, respondWith(400, "a\xffb"))
	_, err := getAuthorization(t, server, "x")
	assertEqual(t, "Body", "a�b", expectError[*HTTPError](t, err).Body)
}

func TestDoesntFollowRedirects(t *testing.T) {
	server := newTestServer(t, func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Location", "/elsewhere")
		w.WriteHeader(302)
	})
	_, err := getAuthorization(t, server, "x")
	assertEqual(t, "Status", 302, expectError[*HTTPError](t, err).Status)
	expectRequests(t, server, 1)
}

func TestRetriesTooManyRequestsAndServerErrors(t *testing.T) {
	for _, status := range []int{429, 500, 503} {
		server := newTestServer(t, respondWith(status, "busy"), respondWith(200, validBody))
		if _, err := getAuthorization(t, server, "x"); err != nil {
			t.Fatalf("%d: %v", status, err)
		}
		expectRequests(t, server, 2)
	}
}

func TestReportsTheLastErrorAfterThreeRetries(t *testing.T) {
	server := newTestServer(t, respondWith(500, "first"), respondWith(500, "second"), respondWith(500, "third"),
		respondWith(503, "last"))
	_, err := getAuthorization(t, server, "x")
	httpError := expectError[*HTTPError](t, err)

	assertEqual(t, "Status", 503, httpError.Status)
	assertEqual(t, "Body", "last", httpError.Body)
	expectRequests(t, server, 4)
}

func TestDoesntRetryOtherResponses(t *testing.T) {
	server := newTestServer(t, respondWith(400, "bad"), respondWith(200, validBody))
	_, err := getAuthorization(t, server, "x")
	expectError[*HTTPError](t, err)
	expectRequests(t, server, 1)
}

func TestDoesntRetryUnusableAuthorizations(t *testing.T) {
	server := newTestServer(t, respondWith(200, `{"status":"failed"}`), respondWith(200, validBody))
	_, err := getAuthorization(t, server, "x")
	assertEqual(t, "Status", AuthorizationStatusFailed, expectError[*UnusableAuthorizationError](t, err).Status)
	expectRequests(t, server, 1)
}

func TestRetriesAfterTimeouts(t *testing.T) {
	server := newTestServer(t, hang)
	_, err := getAuthorization(t, server, "x")
	transportError := expectError[*TransportError](t, err)

	assertEqual(t, "message", "request to AppLink API timed out", transportError.Error())
	expectRequests(t, server, 4)
}

func TestTimesOutWhileReadingASlowBody(t *testing.T) {
	server := newTestServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100")
		w.WriteHeader(200)
		for range 10 {
			_, _ = w.Write([]byte(" "))
			w.(http.Flusher).Flush()
			select {
			case <-r.Context().Done():
				return
			case <-time.After(100 * time.Millisecond):
			}
		}
	})
	_, err := getAuthorization(t, server, "x", withoutRetries)
	assertEqual(t, "message", "request to AppLink API timed out", expectError[*TransportError](t, err).Error())
}

func TestCallerCancellation(t *testing.T) {
	server := newTestServer(t, hang)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	_, err := GetAuthorization(ctx, "x", testOptions(server, nil))
	expectError[*TransportError](t, err)

	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("expected %v to wrap context.DeadlineExceeded", err)
	}
	expectRequests(t, server, 1)
}

func TestCallerCancellationDuringARetryDelay(t *testing.T) {
	server := newTestServer(t, respondWith(503, "busy"))
	ctx, cancel := context.WithCancel(context.Background())
	_, err := GetAuthorization(ctx, "x", testOptions(server, nil), func(o *options) {
		o.retryDelays = []time.Duration{time.Hour}
		time.AfterFunc(100*time.Millisecond, cancel)
	})
	expectError[*TransportError](t, err)

	if !errors.Is(err, context.Canceled) {
		t.Errorf("expected %v to wrap context.Canceled", err)
	}
	expectRequests(t, server, 1)
}

func TestConnectionFailures(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	_, err := GetAuthorization(context.Background(), "x", testOptions(server, map[string]string{
		"HEROKU_APPLINK_API_URL": "https://127.0.0.1:1/addons/abc",
	}))
	transportError := expectError[*TransportError](t, err)

	if !strings.Contains(transportError.Error(), `"https://127.0.0.1:1/addons/abc/authorizations/x"`) {
		t.Errorf("the error doesn't contain the request URL: %v", err)
	}
}

func TestUntrustedCertificates(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody))
	_, err := getAuthorization(t, server, "x", withoutRetries, func(o *options) {
		o.transport = &http.Transport{}
	})
	expectError[*TransportError](t, err)
	expectRequests(t, server, 0)
}

func TestRejects2xxBodiesLargerThan1MiB(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody+strings.Repeat(" ", mib)))
	_, err := getAuthorization(t, server, "x")
	expectError[*UnexpectedResponseError](t, err)
}

func TestAccepts2xxBodiesOfExactly1MiB(t *testing.T) {
	server := newTestServer(t, respondWith(200, validBody+strings.Repeat(" ", mib-len(validBody))))
	context, err := getAuthorization(t, server, "x")
	if err != nil {
		t.Fatal(err)
	}
	assertEqual(t, "DeveloperName", "example", context.AuthorizationMetadata.DeveloperName)
}

func TestTruncatesErrorBodiesTo1MiB(t *testing.T) {
	server := newTestServer(t, respondWith(400, strings.Repeat("x", 2*mib)))
	_, err := getAuthorization(t, server, "x")
	assertEqual(t, "Body", mib, len(expectError[*HTTPError](t, err).Body))
}

func TestUnexpectedResponsesDontLeakTheBody(t *testing.T) {
	server := newTestServer(t, respondWith(200, `{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}`))
	_, err := getAuthorization(t, server, "x")
	if strings.Contains(expectError[*UnexpectedResponseError](t, err).Error(), "secret-token") {
		t.Errorf("the error leaks the access token: %v", err)
	}
}

func TestReadsConfigVarsFromTheEnvironment(t *testing.T) {
	t.Setenv("HEROKU_APPLINK_API_URL", "https://127.0.0.1:1")
	t.Setenv("HEROKU_APPLINK_TOKEN", "t")
	t.Setenv("HEROKU_APP_ID", "a")
	_, err := GetAuthorization(context.Background(), "x", func(o *options) { o.retryDelays = nil })
	transportError := expectError[*TransportError](t, err)

	if !strings.Contains(transportError.Error(), "failed") {
		t.Errorf("unexpected error %v", err)
	}
}
