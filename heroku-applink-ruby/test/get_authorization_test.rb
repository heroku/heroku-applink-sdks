# frozen_string_literal: true

require "test_helper"
require_relative "support/tls_server"

class GetAuthorizationTest < Minitest::Test
  AppLink = Heroku::AppLink
  Client = Heroku::AppLink::Internal::AuthorizationClient
  HttpSettings = Heroku::AppLink::Internal::HttpSettings

  MIB = 1024 * 1024
  VALID_BODY = Vectors.load("authorization-response.json").first["body"]
  SERVER = TlsServer.new
  Minitest.after_run { SERVER.stop }

  def setup
    @settings = HttpSettings.new(timeout: 0.5, retry_delays: [0.001, 0.002, 0.003], cert_store: SERVER.cert_store)
    @env = {
      "HEROKU_APPLINK_API_URL" => "#{SERVER.url}/addons/abc/",
      "HEROKU_APPLINK_TOKEN" => "addon-token",
      "HEROKU_APP_ID" => "app-uuid"
    }
  end

  def respond(...)
    SERVER.respond(...)
  end

  def respond_with(...)
    TlsServer.respond_with(...)
  end

  def get(env = @env, settings = @settings, developer_name = "x", attachment: nil)
    Client.get(env, settings, developer_name, attachment: attachment)
  end

  def test_sends_the_documented_request
    respond(respond_with(200, VALID_BODY))
    context = get(@env, @settings, "my org/x")

    assert_equal "00DJS0000000123!AQ0AQ.token", context.credentials.access_token
    assert_equal 1, SERVER.requests.size
    request = SERVER.requests.first

    assert_equal "GET", request.http_method
    assert_equal "/addons/abc/authorizations/my%20org%2Fx", request.path
    assert_equal "Bearer addon-token", request.headers["authorization"]
    assert_equal "app-uuid", request.headers["x-app-uuid"]
    assert_equal "application/json", request.headers["accept"]
    assert_equal Client::USER_AGENT, request.headers["user-agent"]
  end

  def test_sends_dot_segments_as_is
    %w[. ..].each do |developer_name|
      respond(respond_with(200, VALID_BODY))
      get(@env, @settings, developer_name)

      assert_equal "/addons/abc/authorizations/#{developer_name}", SERVER.requests.first.path
    end
  end

  def test_configuration_errors_send_no_request
    respond(respond_with(200, VALID_BODY))
    assert_raises(AppLink::ConfigurationError) { get(@env, @settings, "") }
    assert_raises(AppLink::ConfigurationError) { get(@env, @settings, nil) }
    assert_raises(AppLink::ConfigurationError) { get(attachment: "") }
    assert_raises(AppLink::ConfigurationError) { get(@env.merge("HEROKU_APP_ID" => "")) }

    assert_empty SERVER.requests
  end

  def test_config_vars_the_request_cant_be_built_from
    respond(respond_with(200, VALID_BODY))
    [
      ["HEROKU_APPLINK_TOKEN", "secret\ntoken"],
      ["HEROKU_APP_ID", "app-uuid\r\nX-Injected: secret"],
      ["HEROKU_APPLINK_API_URL", "https://127.0.0.1:1/a b"],
      ["HEROKU_APPLINK_API_URL", "https:///addons/abc"]
    ].each do |name, value|
      error = assert_raises(AppLink::ConfigurationError) { get(@env.merge(name => value)) }

      refute_includes error.message, "secret"
      assert_nil error.cause
    end

    assert_empty SERVER.requests
  end

  def test_reports_status_and_body
    respond(respond_with(404, '{"message":"not found"}'))
    error = assert_raises(AppLink::HTTPError) { get }

    assert_equal 404, error.status
    assert_equal '{"message":"not found"}', error.body
    assert_equal 1, SERVER.requests.size
  end

  def test_replaces_invalid_utf8_in_error_bodies
    respond(respond_with(400, "a\xFFb".b))
    error = assert_raises(AppLink::HTTPError) { get }

    assert_equal "a�b", error.body
    assert_equal Encoding::UTF_8, error.body.encoding
  end

  def test_doesnt_follow_redirects
    respond(respond_with(302, "", {"Location" => "/elsewhere"}))
    error = assert_raises(AppLink::HTTPError) { get }

    assert_equal 302, error.status
    assert_equal 1, SERVER.requests.size
  end

  [429, 500, 503].each do |status|
    define_method("test_retries_#{status}") do
      respond(respond_with(status, "busy"), respond_with(200, VALID_BODY))
      get

      assert_equal 2, SERVER.requests.size
    end
  end

  def test_reports_the_last_error_after_three_retries
    respond(respond_with(500, "first"), respond_with(500, "second"), respond_with(500, "third"),
      respond_with(503, "last"))
    error = assert_raises(AppLink::HTTPError) { get }

    assert_equal 503, error.status
    assert_equal "last", error.body
    assert_equal 4, SERVER.requests.size
  end

  def test_doesnt_retry_other_responses
    respond(respond_with(400, "bad"), respond_with(200, VALID_BODY))
    assert_raises(AppLink::HTTPError) { get }

    assert_equal 1, SERVER.requests.size
  end

  def test_doesnt_retry_unusable_authorizations
    respond(respond_with(200, '{"status":"failed"}'), respond_with(200, VALID_BODY))
    assert_raises(AppLink::UnusableAuthorizationError) { get }

    assert_equal 1, SERVER.requests.size
  end

  def test_retries_after_timeouts
    respond(->(_socket) { sleep 1 })
    assert_raises(AppLink::TransportError) { get }

    assert_equal 4, SERVER.requests.size
  end

  def test_times_out_while_reading_a_slow_body
    respond(lambda do |socket|
      socket.write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n")
      10.times do
        socket.write(" ")
        sleep 0.1
      end
    end)
    no_retries = HttpSettings.new(timeout: 0.5, retry_delays: [], cert_store: SERVER.cert_store)
    error = assert_raises(AppLink::TransportError) { get(@env, no_retries) }

    assert_equal "The request to the AppLink API timed out.", error.message
  end

  def test_times_out_during_a_stalled_tls_handshake
    stalled = TCPServer.new("127.0.0.1", 0)
    accepted = []
    acceptor = Thread.new { loop { accepted << stalled.accept } }
    no_retries = HttpSettings.new(timeout: 0.5, retry_delays: [], cert_store: SERVER.cert_store)
    started = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    error = assert_raises(AppLink::TransportError) do
      get(@env.merge("HEROKU_APPLINK_API_URL" => "https://127.0.0.1:#{stalled.addr[1]}"), no_retries)
    end

    assert_equal "The request to the AppLink API timed out.", error.message
    assert_operator Process.clock_gettime(Process::CLOCK_MONOTONIC) - started, :<, 1.0
  ensure
    acceptor&.kill
    accepted&.each(&:close)
    stalled&.close
  end

  def test_connection_failures
    assert_raises(AppLink::TransportError) { get(@env.merge("HEROKU_APPLINK_API_URL" => "https://127.0.0.1:1")) }
  end

  def test_untrusted_certificates
    respond(respond_with(200, VALID_BODY))
    assert_raises(AppLink::TransportError) { get(@env, HttpSettings.new(timeout: 0.5, retry_delays: [])) }
  end

  def test_rejects_2xx_bodies_larger_than_1_mib
    respond(respond_with(200, VALID_BODY + (" " * MIB)))
    assert_raises(AppLink::UnexpectedResponseError) { get }
  end

  def test_accepts_2xx_bodies_of_exactly_1_mib
    respond(respond_with(200, VALID_BODY + (" " * (MIB - VALID_BODY.bytesize))))

    assert_equal "example", get.authorization_metadata.developer_name
  end

  def test_truncates_error_bodies_to_1_mib
    respond(respond_with(400, "x" * 2 * MIB))
    error = assert_raises(AppLink::HTTPError) { get }

    assert_equal MIB, error.body.bytesize
  end

  def test_unexpected_responses_dont_leak_the_body
    respond(respond_with(200, '{"status":"authorized","org":{"user_auth":{"access_token":"secret-token"}}}'))
    error = assert_raises(AppLink::UnexpectedResponseError) { get }

    refute_includes error.message, "secret-token"
  end

  def test_reads_config_vars_from_the_environment
    respond(respond_with(200, VALID_BODY))
    original = ENV.to_h
    ENV.update("HEROKU_APPLINK_API_URL" => "https://127.0.0.1:1", "HEROKU_APPLINK_TOKEN" => "t", "HEROKU_APP_ID" => "a")
    error = assert_raises(AppLink::TransportError) { AppLink.get_authorization("x") }

    assert_match(/failed/, error.message)
  ensure
    ENV.replace(original)
  end
end
