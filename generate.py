# Copyright (c) 2026, Salesforce, Inc.
# All rights reserved.
# SPDX-License-Identifier: BSD-3-Clause
# For full license text, see the LICENSE file in the repo root or https://opensource.org/licenses/BSD-3-Clause

"""Generates the shared test vectors. Run with: python3 generate.py"""

import base64
import copy
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

OUT = Path(__file__).parent

MISSING = object()


def b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def with_changes(base: dict, changes: dict) -> dict:
    result = copy.deepcopy(base)
    for path, value in changes.items():
        target = result
        *parents, last = path.split(".")
        for parent in parents:
            target = target[parent]
        if value is MISSING:
            del target[last]
        else:
            target[last] = value
    return result


def millis_to_iso(millis: int) -> str:
    instant = datetime(1970, 1, 1, tzinfo=timezone.utc) + timedelta(milliseconds=millis)
    return instant.isoformat(timespec="milliseconds").replace("+00:00", "Z")


# --- Invocation context -------------------------------------------------------

HEADER = {
    "accessToken": "00DJS0000000123!AQ0AQ.token",
    "apiVersion": "62.0",
    "requestId": "00DJS0000000123ABC-33333333-3333-4333-8333-333333333333-2026-10-13T09:15:42.123456Z",
    "orgId": "00DJS0000000123ABC",
    "orgDomainUrl": "https://example.my.salesforce.com",
    "namespace": "example",
    "userContext": {"userId": "005JS000000H123", "username": "user@example.tld"},
    "version": "1.0",
}


def invocation_expected(header: dict) -> dict:
    access_token = header.get("accessToken")
    namespace = header.get("namespace")
    return {
        "requestId": header["requestId"],
        "namespace": namespace or None,
        "org": {
            "id": header["orgId"],
            "domainUrl": header["orgDomainUrl"],
            "apiVersion": header["apiVersion"],
            "type": None,
        },
        "user": {
            "id": header["userContext"]["userId"],
            "username": header["userContext"]["username"],
        },
        "credentials": (
            None if access_token is None else {"accessToken": access_token, "expiration": None}
        ),
    }


def encode_header(value) -> str:
    return b64(json.dumps(value, ensure_ascii=False).encode("utf-8"))


def header_with_plus_or_slash() -> str:
    filler = ""
    while True:
        encoded = encode_header({**HEADER, "filler": filler})
        if "+" in encoded and "/" in encoded:
            return encoded
        filler += "~?"


def invocation_cases() -> list:
    cases = []

    def valid(name, header):
        cases.append(
            {
                "name": name,
                "header": encode_header(header),
                "decoded": json.dumps(header, ensure_ascii=False),
                "expected": invocation_expected(header),
            }
        )

    def invalid(name, header_value, decoded=None):
        case = {"name": name, "header": header_value}
        if decoded is not None:
            case["decoded"] = decoded
        case["error"] = "invalid_inbound_data"
        cases.append(case)

    def invalid_json(name, value):
        text = json.dumps(value, ensure_ascii=False)
        invalid(name, b64(text.encode("utf-8")), text)

    def lenient(name, header_value, header):
        cases.append(
            {
                "name": name,
                "header": header_value,
                "decoded": json.dumps(header, ensure_ascii=False),
                "expected": invocation_expected(header),
                "error": "invalid_inbound_data",
            }
        )

    valid("all fields", HEADER)
    valid("without accessToken", with_changes(HEADER, {"accessToken": MISSING}))
    null_token = with_changes(HEADER, {"accessToken": None})
    cases.append(
        {
            "name": "accessToken null",
            "header": encode_header(null_token),
            "decoded": json.dumps(null_token),
            "expected": {**invocation_expected(HEADER), "credentials": None},
        }
    )
    valid("without namespace", with_changes(HEADER, {"namespace": MISSING}))
    valid("namespace empty", with_changes(HEADER, {"namespace": ""}))
    valid("namespace null", with_changes(HEADER, {"namespace": None}))
    valid(
        "unknown fields are ignored",
        with_changes(
            HEADER,
            {
                "futureField": {"nested": [1, 2, 3]},
                "userContext.futureField": True,
                "orgType": 42,
            },
        ),
    )
    valid(
        "non-ASCII characters",
        with_changes(HEADER, {"userContext.username": "jürgen.müller@example.tld"}),
    )
    cases.append(
        {
            "name": "standard alphabet with + and /",
            "header": header_with_plus_or_slash(),
            "expected": invocation_expected(HEADER),
        }
    )

    invalid("header missing", None)
    invalid("header empty", "")
    invalid("not base64", "%%%not-base64%%%")
    full = encode_header(HEADER)
    lenient("base64 with line breaks", full[:40] + "\n" + full[40:], HEADER)
    lenient("base64 with spaces", full[:40] + " " + full[40:], HEADER)
    lenient("base64 with trailing newline", full + "\n", HEADER)
    padded_header = with_changes(HEADER, {"padding": "x"})
    padded = encode_header(padded_header)
    if not padded.endswith("="):
        padded_header = with_changes(HEADER, {"padding": "xy"})
        padded = encode_header(padded_header)
    assert padded.endswith("=")
    lenient("base64 without padding", padded.rstrip("="), padded_header)
    lenient("base64 with misplaced padding", "=" + padded.rstrip("="), padded_header)
    unpadded = padded.rstrip("=")
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    last = alphabet.index(unpadded[-1])
    assert last & 0b11 == 0
    lenient(
        "base64 with non-zero trailing bits",
        unpadded[:-1] + alphabet[last | 1] + padded[len(unpadded) :],
        padded_header,
    )
    lenient(
        "base64url alphabet",
        header_with_plus_or_slash().replace("+", "-").replace("/", "_"),
        json.loads(base64.b64decode(header_with_plus_or_slash())),
    )
    lenient(
        "invalid UTF-8 in unknown field",
        b64(json.dumps({**HEADER, "futureField": "x"}).encode("utf-8").replace(b'"x"', b'"\xff\xfe"')),
        HEADER,
    )
    lenient(
        "lone surrogate in unknown field",
        b64(json.dumps({**HEADER, "futureField": "x"}).encode("utf-8").replace(b'"x"', b'"\\ud800"')),
        HEADER,
    )
    lenient(
        "duplicate unknown field",
        b64(json.dumps({**HEADER, "futureField": 1}).encode("utf-8").replace(b'"futureField": 1', b'"futureField": 1, "futureField": 2')),
        HEADER,
    )
    lenient(
        "UTF-8 byte order mark",
        b64(b"\xef\xbb\xbf" + json.dumps(HEADER).encode("utf-8")),
        HEADER,
    )
    invalid("not JSON", b64(b"not json"), "not json")
    lenient("trailing data after JSON", b64(json.dumps(HEADER).encode("utf-8") + b" x"), HEADER)
    invalid_json("JSON array", [HEADER])
    invalid_json("JSON string", "1.0")
    invalid_json("JSON null", None)
    invalid_json("version missing", with_changes(HEADER, {"version": MISSING}))
    invalid_json("version null", with_changes(HEADER, {"version": None}))
    invalid_json("version 2.0", with_changes(HEADER, {"version": "2.0"}))
    invalid_json("version 1", with_changes(HEADER, {"version": "1"}))
    invalid_json("version as number", with_changes(HEADER, {"version": 1.0}))
    for field in [
        "apiVersion",
        "requestId",
        "orgId",
        "orgDomainUrl",
        "userContext",
        "userContext.userId",
        "userContext.username",
    ]:
        invalid_json(f"{field} missing", with_changes(HEADER, {field: MISSING}))
        invalid_json(f"{field} null", with_changes(HEADER, {field: None}))
        if field != "userContext":
            invalid_json(f"{field} empty", with_changes(HEADER, {field: ""}))
            invalid_json(f"{field} not a string", with_changes(HEADER, {field: 62}))
    invalid_json("userContext not an object", with_changes(HEADER, {"userContext": "user"}))
    invalid_json("accessToken empty", with_changes(HEADER, {"accessToken": ""}))
    invalid_json("accessToken not a string", with_changes(HEADER, {"accessToken": 123}))
    invalid_json("namespace not a string", with_changes(HEADER, {"namespace": 123}))
    return cases


# --- Authorization response ---------------------------------------------------

RESPONSE = {
    "id": "22222222-2222-4222-8222-222222222222",
    "status": "authorized",
    "created_at": "2026-10-13T09:15:42.226577Z",
    "created_by": "user@example.tld",
    "created_via_app": "example-app",
    "last_modified_at": "2026-10-14T10:16:43.5Z",
    "last_modified_by": "admin@example.tld",
    "redirect_uri": "https://example.tld/callback",
    "org": {
        "id": "00DJS0000000123ABC",
        "developer_name": "example",
        "instance_url": "https://example.my.salesforce.com",
        "type": "SalesforceOrg",
        "api_version": "62.0",
        "user_auth": {
            "username": "user@example.tld",
            "user_id": "005JS000000H123",
            "access_token": "00DJS0000000123!AQ0AQ.token",
            "expiration": 1791890142226,
        },
    },
}


def authorization_expected(response: dict, **overrides) -> dict:
    org = response["org"]
    user_auth = org["user_auth"]
    expiration = user_auth.get("expiration")
    expected = {
        "authorizationMetadata": {
            "id": response["id"],
            "developerName": org["developer_name"],
            "createdAt": response["created_at"],
            "createdBy": response["created_by"],
            "createdViaApp": response.get("created_via_app") or None,
            "lastModifiedAt": response["last_modified_at"],
            "lastModifiedBy": response.get("last_modified_by") or None,
            "redirectUri": response.get("redirect_uri") or None,
        },
        "org": {
            "id": org["id"],
            "domainUrl": org["instance_url"],
            "apiVersion": org["api_version"],
            "type": org["type"],
        },
        "user": {"id": user_auth["user_id"], "username": user_auth["username"]},
        "credentials": {
            "accessToken": user_auth["access_token"],
            "expiration": millis_to_iso(expiration) if expiration else None,
        },
    }
    for path, value in overrides.items():
        target = expected
        *parents, last = path.split(".")
        for parent in parents:
            target = target[parent]
        target[last] = value
    return expected


def authorization_cases() -> list:
    cases = []

    def valid(name, response, **overrides):
        cases.append(
            {
                "name": name,
                "body": json.dumps(response, ensure_ascii=False),
                "expected": authorization_expected(response, **overrides),
            }
        )

    def unexpected(name, body):
        if not isinstance(body, str):
            body = json.dumps(body, ensure_ascii=False)
        cases.append({"name": name, "body": body, "error": "unexpected_response"})

    def lenient(name, body, response, **overrides):
        if not isinstance(body, str):
            body = json.dumps(body, ensure_ascii=False)
        cases.append(
            {
                "name": name,
                "body": body,
                "expected": authorization_expected(response, **overrides),
                "error": "unexpected_response",
            }
        )

    def unusable(name, body, status):
        cases.append(
            {
                "name": name,
                "body": json.dumps(body),
                "error": "unusable_authorization",
                "status": status,
            }
        )

    valid("all fields", RESPONSE)
    for field in ["created_via_app", "last_modified_by", "redirect_uri", "org.user_auth.expiration"]:
        valid(f"{field} missing", with_changes(RESPONSE, {field: MISSING}))
        valid(f"{field} null", with_changes(RESPONSE, {field: None}))
    for field in ["created_via_app", "last_modified_by", "redirect_uri"]:
        valid(f"{field} empty", with_changes(RESPONSE, {field: ""}))
    valid("expiration 0", with_changes(RESPONSE, {"org.user_auth.expiration": 0}))
    valid("DataCloudOrg", with_changes(RESPONSE, {"org.type": "DataCloudOrg"}))
    valid(
        "timestamps without fraction",
        with_changes(RESPONSE, {"created_at": "2026-10-13T09:15:42Z"}),
    )
    valid(
        "timestamps with millisecond fraction",
        with_changes(RESPONSE, {"created_at": "2026-10-13T09:15:42.226Z"}),
    )
    valid(
        "unknown fields are ignored",
        with_changes(
            RESPONSE,
            {
                "future_field": {"nested": [1, 2, 3]},
                "org.future_field": None,
                "org.user_auth.future_field": "x",
            },
        ),
    )
    valid(
        "non-ASCII characters",
        with_changes(RESPONSE, {"org.user_auth.username": "jürgen.müller@example.tld"}),
    )

    minimal = {"id": RESPONSE["id"], "status": "authorized"}
    for status in ["authorizing", "disconnecting", "disconnected", "failed"]:
        unusable(f"status {status}", with_changes(RESPONSE, {"status": status}), status)
        unusable(f"status {status} without other fields", {**minimal, "status": status}, status)
    unusable("unusable status is checked before other fields", {"status": "failed", "org": 1}, "failed")

    unexpected("not JSON", "not json")
    unexpected("empty body", "")
    unexpected("JSON array", [RESPONSE])
    unexpected("JSON null", None)
    lenient("trailing data after JSON", json.dumps(RESPONSE) + " x", RESPONSE)
    lenient(
        "duplicate unknown field",
        json.dumps({**RESPONSE, "future_field": 1}).replace('"future_field": 1', '"future_field": 1, "future_field": 2'),
        RESPONSE,
    )
    unexpected("status missing", with_changes(RESPONSE, {"status": MISSING}))
    unexpected("status null", with_changes(RESPONSE, {"status": None}))
    unexpected("status unknown", with_changes(RESPONSE, {"status": "pending"}))
    unexpected("status in upper case", with_changes(RESPONSE, {"status": "AUTHORIZED"}))
    unexpected("status not a string", with_changes(RESPONSE, {"status": 1}))
    for field in [
        "id",
        "created_at",
        "created_by",
        "last_modified_at",
        "org",
        "org.id",
        "org.developer_name",
        "org.instance_url",
        "org.type",
        "org.api_version",
        "org.user_auth",
        "org.user_auth.username",
        "org.user_auth.user_id",
        "org.user_auth.access_token",
    ]:
        unexpected(f"{field} missing", with_changes(RESPONSE, {field: MISSING}))
        unexpected(f"{field} null", with_changes(RESPONSE, {field: None}))
        if field not in ["org", "org.user_auth"]:
            unexpected(f"{field} not a string", with_changes(RESPONSE, {field: 1}))
    for field in ["created_at", "last_modified_at", "org.type"]:
        unexpected(f"{field} empty", with_changes(RESPONSE, {field: ""}))
    for field in [
        "id",
        "created_by",
        "org.id",
        "org.developer_name",
        "org.instance_url",
        "org.api_version",
        "org.user_auth.username",
        "org.user_auth.user_id",
        "org.user_auth.access_token",
    ]:
        empty = with_changes(RESPONSE, {field: ""})
        lenient(f"{field} empty", empty, empty)
    for field in ["created_via_app", "last_modified_by", "redirect_uri"]:
        unexpected(f"{field} not a string", with_changes(RESPONSE, {field: 1}))
    unexpected("org not an object", with_changes(RESPONSE, {"org": "org"}))
    unexpected("org.user_auth not an object", with_changes(RESPONSE, {"org.user_auth": []}))
    unexpected("org.type unknown", with_changes(RESPONSE, {"org.type": "OtherOrg"}))
    unexpected("org.type in lower case", with_changes(RESPONSE, {"org.type": "salesforceorg"}))
    unexpected("created_at not a timestamp", with_changes(RESPONSE, {"created_at": "yesterday"}))
    lenient(
        "timestamp with offset",
        with_changes(RESPONSE, {"created_at": "2026-10-13T11:15:42.226577+02:00"}),
        RESPONSE,
    )
    lenient(
        "timestamp with lower-case t and z",
        with_changes(RESPONSE, {"created_at": "2026-10-13t09:15:42.226577z"}),
        RESPONSE,
    )
    unexpected(
        "expiration as string",
        with_changes(RESPONSE, {"org.user_auth.expiration": "1791890142226"}),
    )
    negative = with_changes(RESPONSE, {"org.user_auth.expiration": -1})
    lenient("expiration negative", negative, negative)
    unexpected(
        "expiration with fraction",
        json.dumps(RESPONSE).replace("1791890142226", "1791890142226.5"),
    )
    lenient(
        "expiration with zero fraction",
        json.dumps(RESPONSE).replace("1791890142226", "1791890142226.0"),
        RESPONSE,
    )
    lenient(
        "expiration in exponent notation",
        json.dumps(RESPONSE).replace("1791890142226", "1.791890142226e12"),
        RESPONSE,
    )
    unexpected(
        "expiration beyond 64 bits",
        with_changes(RESPONSE, {"org.user_auth.expiration": 100000000000000000000}),
    )
    valid(
        "expiration at the end of year 9999",
        with_changes(RESPONSE, {"org.user_auth.expiration": 253402300799999}),
    )
    return cases


# --- Attachment resolution ----------------------------------------------------

APP_ID = "11111111-1111-4111-8111-111111111111"
DEFAULT_ENV = {
    "HEROKU_APPLINK_API_URL": "https://applink.example.tld/addons/default",
    "HEROKU_APPLINK_TOKEN": "default-token",
    "HEROKU_APP_ID": APP_ID,
}
PURPLE_ENV = {
    "HEROKU_APPLINK_PURPLE_API_URL": "https://applink.example.tld/addons/purple",
    "HEROKU_APPLINK_PURPLE_TOKEN": "purple-token",
}


def attachment_cases() -> list:
    cases = []

    def resolved(name, env, attachment, api_url, token):
        cases.append(
            {
                "name": name,
                "env": env,
                "attachment": attachment,
                "expected": {"apiUrl": api_url, "token": token, "appId": env["HEROKU_APP_ID"]},
            }
        )

    def configuration_error(name, env, attachment):
        cases.append(
            {"name": name, "env": env, "attachment": attachment, "error": "configuration"}
        )

    both = {**DEFAULT_ENV, **PURPLE_ENV}
    default_url = DEFAULT_ENV["HEROKU_APPLINK_API_URL"]
    purple_url = PURPLE_ENV["HEROKU_APPLINK_PURPLE_API_URL"]

    resolved("default attachment", both, None, default_url, "default-token")
    configuration_error(
        "default API URL missing", with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": MISSING}), None
    )
    configuration_error(
        "default API URL empty", with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": ""}), None
    )
    configuration_error(
        "default token missing", with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_TOKEN": MISSING}), None
    )
    configuration_error(
        "default token empty", with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_TOKEN": ""}), None
    )
    configuration_error(
        "app ID missing", with_changes(DEFAULT_ENV, {"HEROKU_APP_ID": MISSING}), None
    )
    configuration_error("app ID empty", with_changes(DEFAULT_ENV, {"HEROKU_APP_ID": ""}), None)
    configuration_error("attachment empty", both, "")

    resolved("attachment name", both, "HEROKU_APPLINK_PURPLE", purple_url, "purple-token")
    resolved("attachment name in lower case", both, "heroku_applink_purple", purple_url, "purple-token")
    resolved("default attachment by name", both, "HEROKU_APPLINK", default_url, "default-token")
    resolved("color", both, "purple", purple_url, "purple-token")
    resolved("color in upper case", both, "PURPLE", purple_url, "purple-token")
    resolved(
        "custom attachment name",
        {**DEFAULT_ENV, "MY_APPLINK_API_URL": "https://applink.example.tld/addons/custom", "MY_APPLINK_TOKEN": "custom-token"},
        "my_applink",
        "https://applink.example.tld/addons/custom",
        "custom-token",
    )
    resolved(
        "name takes precedence over color",
        {
            **both,
            "PURPLE_API_URL": "https://applink.example.tld/addons/plain-purple",
            "PURPLE_TOKEN": "plain-purple-token",
        },
        "purple",
        "https://applink.example.tld/addons/plain-purple",
        "plain-purple-token",
    )
    resolved(
        "falls back to color when name token is empty",
        {**both, "PURPLE_API_URL": "https://applink.example.tld/addons/plain-purple", "PURPLE_TOKEN": ""},
        "purple",
        purple_url,
        "purple-token",
    )
    resolved(
        "falls back to color when name token is missing",
        {**both, "PURPLE_API_URL": "https://applink.example.tld/addons/plain-purple"},
        "purple",
        purple_url,
        "purple-token",
    )
    resolved(
        "falls back to color when name API URL is empty",
        {**both, "PURPLE_API_URL": "", "PURPLE_TOKEN": "plain-purple-token"},
        "purple",
        purple_url,
        "purple-token",
    )
    configuration_error(
        "name without token and no color",
        {**DEFAULT_ENV, "PURPLE_API_URL": "https://applink.example.tld/addons/plain-purple"},
        "purple",
    )
    configuration_error("unknown color", both, "orange")
    configuration_error(
        "color token missing",
        with_changes(both, {"HEROKU_APPLINK_PURPLE_TOKEN": MISSING}),
        "purple",
    )

    resolved("API URL", both, purple_url, purple_url, "purple-token")
    resolved(
        "API URL ignoring case and trailing slash",
        both,
        "HTTPS://APPLINK.EXAMPLE.TLD/ADDONS/PURPLE/",
        purple_url,
        "purple-token",
    )
    resolved(
        "API URL with trailing slash in config var",
        with_changes(both, {"HEROKU_APPLINK_PURPLE_API_URL": purple_url + "/"}),
        purple_url,
        purple_url,
        "purple-token",
    )
    resolved(
        "API URL of custom attachment",
        {**DEFAULT_ENV, "MY_APPLINK_API_URL": "https://applink.example.tld/addons/custom", "MY_APPLINK_TOKEN": "custom-token"},
        "https://applink.example.tld/addons/custom",
        "https://applink.example.tld/addons/custom",
        "custom-token",
    )
    resolved("API URL of default attachment", both, default_url, default_url, "default-token")
    configuration_error(
        "API URL with a non-ASCII letter matches no config var",
        both,
        "https://applin\u212a.example.tld/addons/purple",
    )
    configuration_error("API URL matches no config var", both, "https://applink.example.tld/addons/orange")
    configuration_error(
        "API URL matches several config vars ignoring case and trailing slash",
        {**both, "OTHER_API_URL": purple_url.upper() + "/", "OTHER_TOKEN": "other-token"},
        purple_url,
    )
    configuration_error(
        "API URL matches several config vars",
        {**both, "OTHER_API_URL": purple_url, "OTHER_TOKEN": "other-token"},
        purple_url,
    )
    configuration_error(
        "API URL matches config var without token",
        with_changes(both, {"HEROKU_APPLINK_PURPLE_TOKEN": MISSING}),
        purple_url,
    )
    configuration_error(
        "API URL only matches config var without underscore before API_URL",
        {**DEFAULT_ENV, "PURPLEAPI_URL": purple_url, "PURPLETOKEN": "purple-token"},
        purple_url,
    )
    configuration_error(
        "API URL with other scheme matches no config var", both, "ftp://applink.example.tld/addons/purple"
    )

    resolved(
        "trailing slash is removed from API URL",
        with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": default_url + "/"}),
        None,
        default_url,
        "default-token",
    )
    resolved(
        "https scheme in upper case",
        with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": "HTTPS://applink.example.tld/addons/default"}),
        None,
        "HTTPS://applink.example.tld/addons/default",
        "default-token",
    )
    configuration_error(
        "http API URL",
        with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": "http://applink.example.tld/addons/default"}),
        None,
    )
    configuration_error(
        "http API URL matched by URL",
        {**DEFAULT_ENV, "PLAIN_API_URL": "http://applink.example.tld/addons/plain", "PLAIN_TOKEN": "plain-token"},
        "http://applink.example.tld/addons/plain",
    )
    configuration_error(
        "API URL without scheme",
        with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": "applink.example.tld/addons/default"}),
        None,
    )
    configuration_error(
        "https scheme with a non-ASCII letter",
        with_changes(DEFAULT_ENV, {"HEROKU_APPLINK_API_URL": "http\u017f://applink.example.tld/addons/default"}),
        None,
    )
    return cases


# --- Request path -------------------------------------------------------------


def request_path_cases() -> list:
    names = {
        "example": "example",
        "my-org_1.x~": "my-org_1.x~",
        "my org": "my%20org",
        "a/b": "a%2Fb",
        "a?b#c": "a%3Fb%23c",
        "a+b": "a%2Bb",
        "50%": "50%25",
        "a:b@c!d$e&f'g(h)i*j,k;l=m": "a%3Ab%40c%21d%24e%26f%27g%28h%29i%2Aj%2Ck%3Bl%3Dm",
        "jürgen": "j%C3%BCrgen",
        "..": "..",
    }
    return [
        {"developerName": name, "path": "/authorizations/" + encoded}
        for name, encoded in names.items()
    ]


def write(name: str, cases: list) -> None:
    (OUT / name).write_text(json.dumps({"cases": cases}, indent=2, ensure_ascii=False) + "\n")


write("invocation-context.json", invocation_cases())
write("authorization-response.json", authorization_cases())
write("attachment-resolution.json", attachment_cases())
write("request-path.json", request_path_cases())
