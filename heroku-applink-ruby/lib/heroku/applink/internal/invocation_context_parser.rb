# frozen_string_literal: true

module Heroku
  module AppLink
    module Internal
      # @api private
      module InvocationContextParser
        HEADER_NAME = "x-client-context"

        module_function

        def parse(header)
          raise InvalidInboundDataError, "The #{HEADER_NAME} header is missing." unless Json.non_empty_string?(header)

          data = Json.parse_object(decode_base64(header))
          raise InvalidInboundDataError, "The #{HEADER_NAME} header doesn't contain a UTF-8 JSON object." if data.nil?
          unless data["version"] == "1.0"
            raise InvalidInboundDataError, "The #{HEADER_NAME} header doesn't have the supported version 1.0."
          end

          user_context = data["userContext"]
          raise invalid_field("userContext") unless user_context.is_a?(Hash)

          access_token = data["accessToken"]
          raise invalid_field("accessToken") unless access_token.nil? || Json.non_empty_string?(access_token)

          namespace = data["namespace"]
          raise invalid_field("namespace") unless namespace.nil? || namespace.is_a?(String)

          InvocationAppLinkContext.new(
            request_id: required_string(data, "requestId"),
            namespace: namespace.nil? || namespace.empty? ? nil : namespace,
            org: Org.new(
              id: required_string(data, "orgId"),
              domain_url: required_string(data, "orgDomainUrl"),
              api_version: required_string(data, "apiVersion")
            ),
            user: User.new(
              id: required_string(user_context, "userId", "userContext."),
              username: required_string(user_context, "username", "userContext.")
            ),
            credentials: access_token.nil? ? nil : Credentials.new(access_token: access_token)
          )
        end

        def decode_base64(header)
          header.unpack1("m0")
        rescue ArgumentError
          raise InvalidInboundDataError, "The #{HEADER_NAME} header is not valid base64.", cause: nil
        end

        def required_string(data, field, prefix = "")
          value = data[field]
          raise invalid_field(prefix + field) unless Json.non_empty_string?(value)

          value
        end

        def invalid_field(field)
          InvalidInboundDataError.new("The #{HEADER_NAME} header has no valid \"#{field}\" field.")
        end
      end
    end
  end
end
