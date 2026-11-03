# frozen_string_literal: true

require "test_helper"

class AuthorizationTest < Minitest::Test
  AppLink = Heroku::AppLink
  Parser = Heroku::AppLink::Internal::AuthorizationResponseParser
  Resolver = Heroku::AppLink::Internal::AttachmentResolver

  Vectors.load("authorization-response.json").each do |vector|
    define_method(Vectors.test_name("response #{vector['name']}")) do
      body = vector["body"]
      if vector["error"] == "unusable_authorization"
        error = assert_raises(AppLink::UnusableAuthorizationError) { Parser.parse(body) }

        assert_equal vector["status"].to_sym, error.status
        next
      end
      expected = vector["expected"]
      if expected.nil?
        error = assert_raises(AppLink::UnexpectedResponseError) { Parser.parse(body) }

        assert_nil error.cause
        next
      end

      context = begin
        Parser.parse(body)
      rescue AppLink::UnexpectedResponseError => e
        raise unless vector["error"]

        assert_nil e.cause
        next
      end

      assert_context expected, context
    end
  end

  Vectors.load("attachment-resolution.json").each do |vector|
    define_method(Vectors.test_name("attachment #{vector['name']}")) do
      expected = vector["expected"]
      if expected.nil?
        assert_raises(AppLink::ConfigurationError) { Resolver.resolve(vector["env"], vector["attachment"]) }
        next
      end

      resolved = Resolver.resolve(vector["env"], vector["attachment"])

      assert_equal expected["apiUrl"], resolved.api_url
      assert_equal expected["token"], resolved.token
      assert_equal expected["appId"], resolved.app_id
    end
  end

  Vectors.load("request-path.json").each_with_index do |vector, index|
    define_method(Vectors.test_name("path #{index} #{vector['developerName']}")) do
      assert_equal vector["path"], Parser.path(vector["developerName"])
    end
  end

  def test_response_errors_dont_leak_the_body
    error = assert_raises(AppLink::UnexpectedResponseError) do
      Parser.parse('{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}')
    end

    refute_includes error.message, "secret-token"
  end

  def test_rejects_comments_in_json
    assert_raises(AppLink::UnexpectedResponseError) { Parser.parse('{"status":"authorized"} // comment') }
  end

  private

  def assert_context(expected, context)
    metadata = context.authorization_metadata
    expected_metadata = expected["authorizationMetadata"]

    assert_equal expected_metadata["id"], metadata.id
    assert_equal expected_metadata["developerName"], metadata.developer_name
    assert_time Vectors.instant(expected_metadata["createdAt"]), metadata.created_at
    assert_equal expected_metadata["createdBy"], metadata.created_by
    assert_equal_or_nil expected_metadata["createdViaApp"], metadata.created_via_app
    assert_time Vectors.instant(expected_metadata["lastModifiedAt"]), metadata.last_modified_at
    assert_equal_or_nil expected_metadata["lastModifiedBy"], metadata.last_modified_by
    assert_equal_or_nil expected_metadata["redirectUri"], metadata.redirect_uri
    assert_equal expected["org"]["id"], context.org.id
    assert_equal expected["org"]["domainUrl"], context.org.domain_url
    assert_equal expected["org"]["apiVersion"], context.org.api_version
    assert_equal({ "SalesforceOrg" => :salesforce_org, "DataCloudOrg" => :data_cloud_org }[expected["org"]["type"]],
                 context.org.type)
    assert_equal expected["user"]["id"], context.user.id
    assert_equal expected["user"]["username"], context.user.username
    assert_equal expected["credentials"]["accessToken"], context.credentials.access_token
    assert_time Vectors.instant(expected["credentials"]["expiration"]), context.credentials.expiration
  end

  def assert_time(expected, actual)
    if expected.nil?
      assert_nil actual
    else
      assert_equal expected, actual
      assert_predicate actual, :utc?
    end
  end

  def assert_equal_or_nil(expected, actual)
    expected.nil? ? assert_nil(actual) : assert_equal(expected, actual)
  end
end
