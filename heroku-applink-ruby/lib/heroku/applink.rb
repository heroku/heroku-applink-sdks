# frozen_string_literal: true

require_relative "applink/version"
require_relative "applink/types"
require_relative "applink/errors"
require_relative "applink/internal/json"
require_relative "applink/internal/invocation_context_parser"
require_relative "applink/internal/attachment_resolver"
require_relative "applink/internal/authorization_response_parser"
require_relative "applink/internal/http"
require_relative "applink/internal/authorization_client"

module Heroku
  # The Heroku AppLink SDK. Use {AppLink.parse_invocation_context} to get the context of an
  # invocation from Salesforce, and {AppLink.get_authorization} to look up a stored authorization.
  #
  # @see https://devcenter.heroku.com/articles/heroku-applink
  module AppLink
    module_function

    # Parses the context of an invocation from Salesforce from the +x-client-context+ header of an
    # inbound request.
    #
    # The SDK trusts the header, because AppLink's Service Mesh authenticates requests before they
    # reach the app.
    #
    # @example In a Rails controller
    #   context = Heroku::AppLink.parse_invocation_context { |name| request.headers[name] }
    #
    # @yieldparam name [String] the name of a header, always in lower case
    # @yieldreturn [String, nil] the value of the inbound request's header with the given name, or
    #   +nil+ if the request doesn't have it
    # @return [InvocationAppLinkContext] the context of the invocation
    # @raise [InvalidInboundDataError] if the header is missing or malformed
    # @see https://devcenter.heroku.com/articles/invoking-heroku-applink-apps
    def parse_invocation_context
      raise ArgumentError, "A block that returns header values is required." unless block_given?

      Internal::InvocationContextParser.parse(yield(Internal::InvocationContextParser::HEADER_NAME))
    end

    # Looks up a stored authorization through the AppLink API.
    #
    # Every call sends a request to the AppLink API. Apps can keep the returned context and reuse it
    # until +context.credentials.expiration+.
    #
    # @example With the default attachment, which reads HEROKU_APPLINK_API_URL and HEROKU_APPLINK_TOKEN
    #   context = Heroku::AppLink.get_authorization("<my-authorization>")
    # @example By attachment name, which reads HEROKU_APPLINK_PURPLE_API_URL and HEROKU_APPLINK_PURPLE_TOKEN
    #   context = Heroku::AppLink.get_authorization("<my-authorization>", attachment: "HEROKU_APPLINK_PURPLE")
    # @example By attachment color, which reads the same config vars as the name
    #   context = Heroku::AppLink.get_authorization("<my-authorization>", attachment: "purple")
    # @example By API URL, which uses the attachment whose _API_URL config var holds that URL
    #   api_url = ENV.fetch("HEROKU_APPLINK_PURPLE_API_URL")
    #   context = Heroku::AppLink.get_authorization("<my-authorization>", attachment: api_url)
    #
    # @param developer_name [String] the developer name of the authorization
    # @param attachment [String, nil] the AppLink add-on attachment to use, by name (such as
    #   +HEROKU_APPLINK_PURPLE+), color (such as +purple+), or API URL. Defaults to +HEROKU_APPLINK+.
    # @return [AuthorizationAppLinkContext] the context of the authorization
    # @raise [ConfigurationError] if the developer name or attachment is empty, or the config vars
    #   are missing or invalid
    # @raise [TransportError] if the request to the AppLink API didn't complete
    # @raise [HTTPError] if the AppLink API responded with a status other than 2xx
    # @raise [UnusableAuthorizationError] if the authorization isn't authorized
    # @raise [UnexpectedResponseError] if the response doesn't have the expected content
    # @see https://devcenter.heroku.com/articles/heroku-applink-api
    def get_authorization(developer_name, attachment: nil)
      Internal::AuthorizationClient.get(ENV.to_h, Internal::HttpSettings.new, developer_name, attachment: attachment)
    end
  end
end
