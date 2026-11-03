# frozen_string_literal: true

module Heroku
  module AppLink
    # The types of Salesforce orgs, as returned by the AppLink API: +:salesforce_org+ for a Salesforce
    # org, and +:data_cloud_org+ for a Data 360 org.
    ORG_TYPES = %i[salesforce_org data_cloud_org].freeze

    # The statuses of authorizations, as returned by the AppLink API. Only +:authorized+
    # authorizations can be used. Apps only see the other statuses in an
    # {UnusableAuthorizationError}.
    #
    # - +:authorizing+: The add-on is authorizing the user.
    # - +:authorized+: The user is authorized and the authorization can be used.
    # - +:disconnecting+: The add-on is removing the authorization.
    # - +:disconnected+: The authorization has been removed.
    # - +:failed+: Authorizing failed with an unexpected error.
    #
    # @see https://devcenter.heroku.com/articles/heroku-applink-api
    AUTHORIZATION_STATUSES = %i[authorizing authorized disconnecting disconnected failed].freeze

    # A Salesforce org that the app works with.
    #
    # @!attribute [r] id
    #   @return [String] the ID of the org
    # @!attribute [r] domain_url
    #   @return [String] the org's My Domain URL. Pass it to a Salesforce client as the instance URL.
    # @!attribute [r] api_version
    #   @return [String] the org's Salesforce API version, such as +62.0+
    # @!attribute [r] type
    #   @return [Symbol, nil] the type of the org, one of {ORG_TYPES}. Present in an
    #     {AuthorizationAppLinkContext}, +nil+ in an {InvocationAppLinkContext}.
    Org = Data.define(:id, :domain_url, :api_version, :type) do
      def initialize(id:, domain_url:, api_version:, type: nil)
        super
      end
    end

    # A Salesforce user.
    #
    # @!attribute [r] id
    #   @return [String] the ID of the user
    # @!attribute [r] username
    #   @return [String] the username of the user
    User = Data.define(:id, :username)

    # Credentials for calling Salesforce APIs.
    #
    # +inspect+ and +to_s+ redact the access token, so it doesn't leak into logs by accident.
    #
    # @!attribute [r] access_token
    #   @return [String] the Salesforce access token. Pass it to a Salesforce client as the bearer
    #     token.
    # @!attribute [r] expiration
    #   @return [Time, nil] the instant the access token expires, in UTC. +nil+ if AppLink doesn't
    #     provide one.
    Credentials = Data.define(:access_token, :expiration) do
      def initialize(access_token:, expiration: nil)
        super
      end

      def inspect
        "#<data #{self.class.name} access_token=[REDACTED], expiration=#{expiration.inspect}>"
      end

      # The json gem encodes objects without to_json as their to_s, so JSON-encoding Credentials
      # yields this redacted string. That's a json gem behavior, not serialization by the SDK.
      alias_method :to_s, :inspect

      def pretty_print(pp)
        pp.text(inspect)
      end
    end

    # The org and the user that every context has. {InvocationAppLinkContext} and
    # {AuthorizationAppLinkContext} include this module. It isn't meant to be used directly.
    module AppLinkContext
      # @!method org
      #   @return [Org] the org the context belongs to
      # @!method user
      #   @return [User] the user acting in the org
    end

    # The context of an invocation from Salesforce, decoded from the +x-client-context+ header by
    # {AppLink.parse_invocation_context}. There is one per inbound request. It describes the
    # invoking user.
    #
    # @!attribute [r] org
    #   @return [Org] the invoking org
    # @!attribute [r] user
    #   @return [User] the invoking user
    # @!attribute [r] request_id
    #   @return [String] the ID of the invocation
    # @!attribute [r] namespace
    #   @return [String, nil] the invoking org's namespace prefix. +nil+ when the org has none.
    # @!attribute [r] credentials
    #   @return [Credentials, nil] credentials for acting as the invoking user. +nil+ when the
    #     invocation carries no access token.
    InvocationAppLinkContext = Data.define(:org, :user, :request_id, :namespace, :credentials) do
      include AppLinkContext

      def initialize(org:, user:, request_id:, namespace: nil, credentials: nil)
        super
      end
    end

    # Metadata of an authorization, as returned by the AppLink API.
    #
    # @see https://devcenter.heroku.com/articles/heroku-applink-api
    #
    # @!attribute [r] id
    #   @return [String] the ID of the authorization
    # @!attribute [r] developer_name
    #   @return [String] the developer name of the authorization
    # @!attribute [r] created_at
    #   @return [Time] the instant the authorization was created, in UTC
    # @!attribute [r] created_by
    #   @return [String] who created the authorization
    # @!attribute [r] created_via_app
    #   @return [String, nil] the app the authorization was created through, if any
    # @!attribute [r] last_modified_at
    #   @return [Time] the instant the authorization was last modified, in UTC
    # @!attribute [r] last_modified_by
    #   @return [String, nil] who last modified the authorization, if known
    # @!attribute [r] redirect_uri
    #   @return [String, nil] the redirect URI of the authorization, if any
    AuthorizationMetadata = Data.define(
      :id, :developer_name, :created_at, :created_by, :created_via_app, :last_modified_at, :last_modified_by,
      :redirect_uri
    ) do
      def initialize(id:, developer_name:, created_at:, created_by:, last_modified_at:, created_via_app: nil,
        last_modified_by: nil, redirect_uri: nil)
        super
      end
    end

    # The context of a stored authorization, returned by {AppLink.get_authorization}. It describes
    # the user the authorization was made for. It is long-lived, and apps can keep and reuse it
    # until +credentials.expiration+.
    #
    # @!attribute [r] org
    #   @return [Org] the authorized org
    # @!attribute [r] user
    #   @return [User] the authorized user
    # @!attribute [r] authorization_metadata
    #   @return [AuthorizationMetadata] the metadata of the authorization
    # @!attribute [r] credentials
    #   @return [Credentials] credentials for acting as the authorized user
    AuthorizationAppLinkContext = Data.define(:org, :user, :authorization_metadata, :credentials) do
      include AppLinkContext
    end
  end
end
