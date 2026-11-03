# frozen_string_literal: true

require "time"

module Heroku
  module AppLink
    module Internal
      # @api private
      module AuthorizationResponseParser
        MAX_EXPIRATION = 253_402_300_799_999
        OFFSET = /(?:[Zz]|[+-][0-9]{2}:[0-9]{2})\z/
        ORG_TYPES = {"SalesforceOrg" => :salesforce_org, "DataCloudOrg" => :data_cloud_org}.freeze
        STATUSES = AUTHORIZATION_STATUSES.to_h { |status| [status.to_s, status] }.freeze

        module_function

        def path(developer_name)
          "/authorizations/#{developer_name.b.gsub(/[^A-Za-z0-9\-._~]/n) { |byte| format("%%%02X", byte.ord) }}"
        end

        def parse(body)
          data = Json.parse_object(body)
          raise UnexpectedResponseError, "The AppLink API response is not a JSON object." if data.nil?

          status = STATUSES[data["status"]] if data["status"].is_a?(String)
          raise invalid_field("status") if status.nil?
          unless status == :authorized
            raise UnusableAuthorizationError.new(
              "The authorization has the status #{status} instead of authorized.", status
            )
          end

          org = required_object(data, "org")
          user_auth = required_object(org, "user_auth", "org.")
          org_type = ORG_TYPES[org["type"]] if org["type"].is_a?(String)
          raise invalid_field("org.type") if org_type.nil?

          AuthorizationAppLinkContext.new(
            authorization_metadata: metadata(data, org),
            org: Org.new(
              id: required_string(org, "id", "org."),
              domain_url: required_string(org, "instance_url", "org."),
              api_version: required_string(org, "api_version", "org."),
              type: org_type
            ),
            user: User.new(
              id: required_string(user_auth, "user_id", "org.user_auth."),
              username: required_string(user_auth, "username", "org.user_auth.")
            ),
            credentials: Credentials.new(
              access_token: required_string(user_auth, "access_token", "org.user_auth."),
              expiration: optional_expiration(user_auth)
            )
          )
        end

        def metadata(data, org)
          AuthorizationMetadata.new(
            id: required_string(data, "id"),
            developer_name: required_string(org, "developer_name", "org."),
            created_at: required_timestamp(data, "created_at"),
            created_by: required_string(data, "created_by"),
            created_via_app: optional_string(data, "created_via_app"),
            last_modified_at: required_timestamp(data, "last_modified_at"),
            last_modified_by: optional_string(data, "last_modified_by"),
            redirect_uri: optional_string(data, "redirect_uri")
          )
        end

        def required_object(data, field, prefix = "")
          value = data[field]
          raise invalid_field(prefix + field) unless value.is_a?(Hash)

          value
        end

        def required_string(data, field, prefix = "")
          value = data[field]
          raise invalid_field(prefix + field) unless Json.non_empty_string?(value)

          value
        end

        def optional_string(data, field)
          value = data[field]
          raise invalid_field(field) unless value.nil? || value.is_a?(String)

          (value.nil? || value.empty?) ? nil : value
        end

        def required_timestamp(data, field)
          value = required_string(data, field)
          timestamp = begin
            Time.iso8601(value) if OFFSET.match?(value)
          rescue ArgumentError
            nil
          end
          raise invalid_field(field) if timestamp.nil?

          timestamp.utc
        end

        def optional_expiration(user_auth)
          value = user_auth["expiration"]
          return nil if value.nil?
          unless value.is_a?(Integer) && value.between?(0, MAX_EXPIRATION)
            raise invalid_field("org.user_auth.expiration")
          end

          value.zero? ? nil : Time.at(0, value, :millisecond, in: "UTC")
        end

        def invalid_field(field)
          UnexpectedResponseError.new("The AppLink API response has no valid \"#{field}\" field.")
        end
      end
    end
  end
end
