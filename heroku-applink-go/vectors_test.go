package applink

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func readVectors(name string) ([]map[string]any, error) {
	data, err := os.ReadFile(filepath.Join("..", "test-vectors", name))
	if err != nil {
		return nil, err
	}
	var vectors struct {
		Cases []map[string]any `json:"cases"`
	}
	err = json.Unmarshal(data, &vectors)
	return vectors.Cases, err
}

func loadVectors(t *testing.T, name string) []map[string]any {
	t.Helper()
	vectors, err := readVectors(name)
	if err != nil {
		t.Fatal(err)
	}
	return vectors
}

func str(value any) string {
	s, _ := value.(string)
	return s
}

func object(value any) map[string]any {
	o, _ := value.(map[string]any)
	return o
}

func instant(t *testing.T, value any) time.Time {
	t.Helper()
	if value == nil {
		return time.Time{}
	}
	parsed, err := time.Parse(time.RFC3339Nano, str(value))
	if err != nil {
		t.Fatal(err)
	}
	return parsed
}

func assertEqual[T comparable](t *testing.T, field string, expected, actual T) {
	t.Helper()
	if expected != actual {
		t.Errorf("%s: expected %#v, got %#v", field, expected, actual)
	}
}

func assertInstant(t *testing.T, field string, expected, actual time.Time) {
	t.Helper()
	if !expected.Equal(actual) {
		t.Errorf("%s: expected %v, got %v", field, expected, actual)
	}
	if actual.Location() != time.UTC {
		t.Errorf("%s: expected UTC, got %v", field, actual.Location())
	}
}
