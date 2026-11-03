# frozen_string_literal: true

require "test_helper"
require "pp"

class TypesTest < Minitest::Test
  AppLink = Heroku::AppLink

  CREDENTIALS = AppLink::Credentials.new(access_token: "secret-token", expiration: Time.utc(2026, 10, 13, 9, 15, 42))

  def test_credentials_inspect_redacts_the_access_token
    assert_equal "#<data Heroku::AppLink::Credentials access_token=[REDACTED], expiration=2026-10-13 09:15:42 UTC>",
                 CREDENTIALS.inspect
    assert_equal CREDENTIALS.inspect, CREDENTIALS.to_s
  end

  def test_context_inspect_redacts_the_access_token
    context = AppLink::InvocationAppLinkContext.new(
      org: AppLink::Org.new(id: "org", domain_url: "https://example.tld", api_version: "62.0"),
      user: AppLink::User.new(id: "user", username: "user@example.tld"),
      request_id: "request",
      credentials: CREDENTIALS
    )

    [context.inspect, context.to_s, context.pretty_inspect].each do |output|
      refute_includes output, "secret-token"
      assert_includes output, "[REDACTED]"
    end
    assert_kind_of AppLink::AppLinkContext, context
  end

  def test_resolved_attachment_inspect_redacts_the_token
    resolved = AppLink::Internal::ResolvedAttachment.new(api_url: "https://example.tld", token: "secret-token",
                                                         app_id: "app")

    refute_includes resolved.inspect, "secret-token"
    refute_includes resolved.pretty_inspect, "secret-token"
  end

  def test_types_are_immutable
    assert_predicate CREDENTIALS, :frozen?
    refute_respond_to CREDENTIALS, :access_token=
  end

  def test_errors_share_a_base_class
    [
      AppLink::InvalidInboundDataError, AppLink::ConfigurationError, AppLink::TransportError, AppLink::HTTPError,
      AppLink::UnusableAuthorizationError, AppLink::UnexpectedResponseError
    ].each { |error| assert_operator error, :<, AppLink::Error }
  end

  def test_user_agent_matches_the_gem_version
    spec = Gem::Specification.load(File.expand_path("../heroku-applink.gemspec", __dir__))

    assert_equal spec.version.to_s, AppLink::VERSION
    assert_equal "heroku-applink-ruby/#{AppLink::VERSION}", AppLink::Internal::AuthorizationClient::USER_AGENT
  end
end
