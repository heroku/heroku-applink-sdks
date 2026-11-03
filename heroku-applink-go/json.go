package applink

import (
	"bytes"
	"encoding/json"
	"io"
	"strconv"
	"unicode/utf8"
)

type jsonObject = map[string]any

func parseJSONObject(data []byte) jsonObject {
	if !utf8.Valid(data) {
		return nil
	}
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.UseNumber()
	var value any
	if decoder.Decode(&value) != nil {
		return nil
	}
	if _, err := decoder.Token(); err != io.EOF {
		return nil
	}
	object, _ := value.(jsonObject)
	return object
}

func nonEmptyString(value any) (string, bool) {
	s, ok := value.(string)
	return s, ok && s != ""
}

func jsonInt64(value any) (int64, bool) {
	number, ok := value.(json.Number)
	if !ok {
		return 0, false
	}
	n, err := strconv.ParseInt(string(number), 10, 64)
	return n, err == nil
}

type fieldReader struct {
	invalid func(field string) error
	err     error
}

func (r *fieldReader) fail(field string) {
	if r.err == nil {
		r.err = r.invalid(field)
	}
}

func (r *fieldReader) get(object jsonObject, prefix, field string) string {
	value, ok := nonEmptyString(object[field])
	if !ok {
		r.fail(prefix + field)
	}
	return value
}
