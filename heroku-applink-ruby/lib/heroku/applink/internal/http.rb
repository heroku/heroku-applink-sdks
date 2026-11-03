# frozen_string_literal: true

require "net/http"
require "openssl"
require "timeout"

module Heroku
  module AppLink
    module Internal
      # @api private
      HttpSettings = Data.define(:timeout, :retry_delays, :cert_store) do
        def initialize(timeout: 5.0, retry_delays: [0.25, 0.5, 1.0], cert_store: nil)
          super
        end
      end

      # @api private
      HttpResponse = Data.define(:status, :body, :body_too_large)

      # @api private
      module Http
        MAX_BODY_BYTES = 1024 * 1024
        TRANSPORT_ERRORS = [
          SystemCallError, IOError, SocketError, OpenSSL::SSL::SSLError, Net::HTTPBadResponse, Net::ProtocolError
        ].freeze

        module_function

        def get_with_retries(uri, path, headers, settings)
          attempt = 0
          loop do
            begin
              response = get(uri, path, headers, settings)
              return response if attempt >= settings.retry_delays.size || !retryable?(response.status)
            rescue TransportError
              raise if attempt >= settings.retry_delays.size
            end
            sleep(settings.retry_delays[attempt])
            attempt += 1
          end
        end

        def retryable?(status)
          status == 429 || status.between?(500, 599)
        end

        def get(uri, path, headers, settings)
          request = build_request(path, headers)
          deadline = Process.clock_gettime(Process::CLOCK_MONOTONIC) + settings.timeout
          http = connection(uri, settings)
          status = nil
          body = "".b
          body_too_large = false
          begin
            # Net::HTTP applies open_timeout to the TCP connect and the TLS handshake separately.
            Timeout.timeout(settings.timeout) { http.start }
            catch(:done) do
              limit_to_deadline(http, deadline)
              http.request(request) do |response|
                status = response.code.to_i
                response.read_body do |chunk|
                  body << chunk.byteslice(0, MAX_BODY_BYTES + 1 - body.bytesize)
                  if body.bytesize > MAX_BODY_BYTES
                    body_too_large = true
                    throw :done
                  end
                  limit_to_deadline(http, deadline)
                end
              end
            end
          rescue Timeout::Error
            raise TransportError, "The request to the AppLink API timed out."
          rescue *TRANSPORT_ERRORS => e
            raise TransportError, "The request to the AppLink API failed: #{e.class}: #{e.message}"
          ensure
            http.finish if http.started?
          end
          HttpResponse.new(status: status, body: body.byteslice(0, MAX_BODY_BYTES), body_too_large: body_too_large)
        end

        def build_request(path, headers)
          Net::HTTP::Get.new(path, headers)
        rescue ArgumentError
          # Raised without the cause, because its message can contain the token.
          raise ConfigurationError, "The config vars don't form a valid AppLink API request.", cause: nil
        end

        def connection(uri, settings)
          http = Net::HTTP.new(uri.hostname, uri.port, nil)
          http.use_ssl = true
          http.verify_mode = OpenSSL::SSL::VERIFY_PEER
          http.cert_store = settings.cert_store unless settings.cert_store.nil?
          http.open_timeout = settings.timeout
          http.read_timeout = settings.timeout
          http.write_timeout = settings.timeout
          http.max_retries = 0
          http
        end

        def limit_to_deadline(http, deadline)
          remaining = deadline - Process.clock_gettime(Process::CLOCK_MONOTONIC)
          raise TransportError, "The request to the AppLink API timed out." if remaining <= 0

          http.read_timeout = remaining
          http.write_timeout = remaining
        end

        def decode_error_body(body)
          body.b.force_encoding(Encoding::UTF_8).scrub("\uFFFD")
        end
      end
    end
  end
end
