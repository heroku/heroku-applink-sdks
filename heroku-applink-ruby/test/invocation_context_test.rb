# frozen_string_literal: true

require "test_helper"

class InvocationContextTest < Minitest::Test
  AppLink = Heroku::AppLink

  Vectors.load("invocation-context.json").each do |vector|
    define_method(Vectors.test_name("vector #{vector['name']}")) do
      get_header = ->(name) { name == "x-client-context" ? vector["header"] : nil }
      expected = vector["expected"]
      if expected.nil?
        assert_raises(AppLink::InvalidInboundDataError) { AppLink.parse_invocation_context(&get_header) }
        next
      end

      context = begin
        AppLink.parse_invocation_context(&get_header)
      rescue AppLink::InvalidInboundDataError
        raise unless vector["error"]

        next
      end

      assert_context expected, context
    end
  end

  def test_asks_for_the_header_in_lower_case
    names = []
    assert_raises(AppLink::InvalidInboundDataError) do
      AppLink.parse_invocation_context do |name|
        names << name
        nil
      end
    end

    assert_equal ["x-client-context"], names
  end

  def test_requires_a_block
    assert_raises(ArgumentError) { AppLink.parse_invocation_context }
  end

  def test_errors_dont_leak_the_header
    header = ['{"accessToken":"secret-token","version":"1.0"'].pack("m0")
    error = assert_raises(AppLink::InvalidInboundDataError) { AppLink.parse_invocation_context { header } }

    refute_includes error.message, "secret-token"
    assert_nil error.cause
  end

  private

  def assert_context(expected, context)
    assert_equal expected["requestId"], context.request_id
    assert_equal_or_nil expected["namespace"], context.namespace
    assert_equal expected["org"]["id"], context.org.id
    assert_equal expected["org"]["domainUrl"], context.org.domain_url
    assert_equal expected["org"]["apiVersion"], context.org.api_version
    assert_nil context.org.type
    assert_equal expected["user"]["id"], context.user.id
    assert_equal expected["user"]["username"], context.user.username
    if expected["credentials"].nil?
      assert_nil context.credentials
    else
      assert_equal expected["credentials"]["accessToken"], context.credentials.access_token
      assert_nil context.credentials.expiration
    end
  end

  def assert_equal_or_nil(expected, actual)
    expected.nil? ? assert_nil(actual) : assert_equal(expected, actual)
  end
end
