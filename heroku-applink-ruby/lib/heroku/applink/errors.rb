# frozen_string_literal: true

module Heroku
  module AppLink
    # The common base class of all errors the SDK raises.
    class Error < StandardError; end

    # Data sent to the app is missing or can't be parsed, for example a missing or malformed
    # +x-client-context+ header.
    class InvalidInboundDataError < Error; end

    # An authorization lookup can't start, because its input or the app's config vars are missing
    # or invalid. Raised before any request is sent.
    class ConfigurationError < Error; end

    # A request to the AppLink API didn't complete, for example because the connection failed or
    # timed out.
    class TransportError < Error; end

    # The AppLink API responded with a status other than 2xx.
    class HTTPError < Error
      # @return [Integer] the HTTP status of the response, such as +404+ for an unknown
      #   authorization
      attr_reader :status

      # @return [String] the response body as UTF-8 text, with invalid bytes replaced. At most 1 MiB
      #   is read.
      attr_reader :body

      # @api private
      def initialize(message, status, body)
        super(message)
        @status = status
        @body = body
      end
    end

    # The AppLink API returned the authorization, but it isn't +:authorized+, for example because
    # it is still being authorized or was disconnected.
    class UnusableAuthorizationError < Error
      # @return [Symbol] the status of the authorization, one of {AUTHORIZATION_STATUSES}
      attr_reader :status

      # @api private
      def initialize(message, status)
        super(message)
        @status = status
      end
    end

    # The AppLink API responded with a 2xx status, but unexpected content, for example a response
    # with a missing required field.
    class UnexpectedResponseError < Error; end
  end
end
