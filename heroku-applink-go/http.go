package applink

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
	"unicode/utf8"
)

const maxBodyBytes = 1024 * 1024

type httpResponse struct {
	status       int
	body         []byte
	bodyTooLarge bool
}

func getWithRetries(ctx context.Context, o *options, requestURL *url.URL, header http.Header) (*httpResponse, error) {
	for attempt := 0; ; attempt++ {
		response, err := get(ctx, o, requestURL, header)
		if ctx.Err() != nil {
			return nil, &TransportError{message: "request to AppLink API was canceled", err: ctx.Err()}
		}
		retryable := err != nil || response.status == http.StatusTooManyRequests ||
			response.status >= 500 && response.status <= 599
		if attempt >= len(o.retryDelays) || !retryable {
			return response, err
		}

		timer := time.NewTimer(o.retryDelays[attempt])
		select {
		case <-ctx.Done():
			timer.Stop()
			return nil, &TransportError{message: "request to AppLink API was canceled", err: ctx.Err()}
		case <-timer.C:
		}
	}
}

func get(ctx context.Context, o *options, requestURL *url.URL, header http.Header) (*httpResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, o.timeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodGet, "", nil)
	if err != nil {
		return nil, transportError(ctx, err)
	}
	request.URL = requestURL
	request.Host = requestURL.Host
	request.Header = header
	client := &http.Client{
		Transport: o.transport,
		CheckRedirect: func(*http.Request, []*http.Request) error {
			return http.ErrUseLastResponse
		},
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, transportError(ctx, err)
	}
	defer response.Body.Close()

	body, err := io.ReadAll(io.LimitReader(response.Body, maxBodyBytes+1))
	if err != nil {
		return nil, transportError(ctx, err)
	}
	return &httpResponse{
		status:       response.StatusCode,
		body:         body[:min(len(body), maxBodyBytes)],
		bodyTooLarge: len(body) > maxBodyBytes,
	}, nil
}

func transportError(ctx context.Context, err error) error {
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return &TransportError{message: "request to AppLink API timed out", err: err}
	}
	return &TransportError{message: fmt.Sprintf("request to AppLink API failed: %v", err), err: err}
}

// decodeErrorBody replaces each maximal invalid subsequence with U+FFFD, like the WHATWG decoder
// and the other AppLink SDKs. strings.ToValidUTF8 would replace whole runs of invalid bytes
// instead.
func decodeErrorBody(body []byte) string {
	var text strings.Builder
	for len(body) > 0 {
		r, size := utf8.DecodeRune(body)
		if r == utf8.RuneError && size == 1 {
			text.WriteRune(utf8.RuneError)
			body = body[invalidSequenceLength(body):]
			continue
		}
		text.Write(body[:size])
		body = body[size:]
	}
	return text.String()
}

func invalidSequenceLength(b []byte) int {
	continuations, lower, upper := 0, byte(0x80), byte(0xBF)
	switch first := b[0]; {
	case 0xC2 <= first && first <= 0xDF:
		continuations = 1
	case first == 0xE0:
		continuations, lower = 2, 0xA0
	case first == 0xED:
		continuations, upper = 2, 0x9F
	case 0xE1 <= first && first <= 0xEF:
		continuations = 2
	case first == 0xF0:
		continuations, lower = 3, 0x90
	case first == 0xF4:
		continuations, upper = 3, 0x8F
	case 0xF1 <= first && first <= 0xF3:
		continuations = 3
	}
	n := 1
	for n <= continuations && n < len(b) && lower <= b[n] && b[n] <= upper {
		n++
		lower, upper = 0x80, 0xBF
	}
	return n
}
