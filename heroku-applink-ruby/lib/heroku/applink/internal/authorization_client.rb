# frozen_string_literal: true

require "uri"

module Heroku
  module AppLink
    module Internal
      # @api private
      module AuthorizationClient
        USER_AGENT = "heroku-applink-ruby/#{VERSION}".freeze

        module_function

        def get(env, settings, developer_name, attachment: nil)
          unless Json.non_empty_string?(developer_name)
            raise ConfigurationError, "The developer name must not be empty."
          end

          resolved = AttachmentResolver.resolve(env, attachment)
          uri = parse_api_url(resolved.api_url)
          response = Http.get_with_retries(
            uri,
            uri.path + AuthorizationResponseParser.path(developer_name),
            {
              "Authorization" => "Bearer #{resolved.token}",
              "X-App-UUID" => resolved.app_id,
              "Accept" => "application/json",
              "Accept-Encoding" => "identity",
              "User-Agent" => USER_AGENT
            },
            settings
          )

          unless response.status.between?(200, 299)
            raise HTTPError.new(
              "The AppLink API responded with HTTP status #{response.status}.",
              response.status,
              Http.decode_error_body(response.body)
            )
          end
          raise UnexpectedResponseError, "The AppLink API response is larger than 1 MiB." if response.body_too_large

          AuthorizationResponseParser.parse(response.body)
        end

        def parse_api_url(api_url)
          uri = begin
            URI.parse(api_url)
          rescue URI::InvalidURIError
            nil
          end
          raise ConfigurationError, "The AppLink API URL is not a valid URL." if uri.nil? || uri.hostname.to_s.empty?

          uri
        end
      end
    end
  end
end
