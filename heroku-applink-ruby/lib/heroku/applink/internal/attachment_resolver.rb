# frozen_string_literal: true

module Heroku
  module AppLink
    module Internal
      # @api private
      ResolvedAttachment = Data.define(:api_url, :token, :app_id) do
        def inspect
          "#<data #{self.class.name} api_url=#{api_url.inspect}, token=[REDACTED], app_id=#{app_id.inspect}>"
        end

        alias_method :to_s, :inspect

        def pretty_print(pp)
          pp.text(inspect)
        end
      end

      # @api private
      module AttachmentResolver
        ABSOLUTE_URL = %r{\A[A-Za-z][A-Za-z0-9+.-]*://}
        # Not using /i, because Ruby's case folding would match non-ASCII letters such as "ſ".
        HTTPS_URL = %r{\A[Hh][Tt][Tt][Pp][Ss]://}

        module_function

        def resolve(env, attachment)
          api_url_var, token_var = config_var_names(env, attachment)
          api_url = required_config_var(env, api_url_var)
          token = required_config_var(env, token_var)
          app_id = required_config_var(env, "HEROKU_APP_ID")
          unless HTTPS_URL.match?(api_url)
            raise ConfigurationError, "The config var #{api_url_var} must contain an https URL."
          end

          ResolvedAttachment.new(api_url: api_url.delete_suffix("/"), token: token, app_id: app_id)
        end

        def config_var_names(env, attachment)
          if attachment.nil?
            %w[HEROKU_APPLINK_API_URL HEROKU_APPLINK_TOKEN]
          elsif attachment.empty?
            raise ConfigurationError, "The attachment must not be empty."
          elsif ABSOLUTE_URL.match?(attachment)
            api_url_var = matching_api_url_var(env, attachment)
            [api_url_var, "#{api_url_var.delete_suffix('API_URL')}TOKEN"]
          else
            name = attachment.upcase(:ascii)
            if present?(env["#{name}_API_URL"]) && present?(env["#{name}_TOKEN"])
              ["#{name}_API_URL", "#{name}_TOKEN"]
            else
              ["HEROKU_APPLINK_#{name}_API_URL", "HEROKU_APPLINK_#{name}_TOKEN"]
            end
          end
        end

        def matching_api_url_var(env, attachment)
          wanted = normalize_url(attachment)
          matches = env.select { |name, value| name.end_with?("_API_URL") && normalize_url(value) == wanted }.keys.sort
          if matches.empty?
            raise ConfigurationError, "No config var ending in _API_URL matches the attachment URL #{attachment}."
          end
          if matches.size > 1
            raise ConfigurationError,
                  "Several config vars match the attachment URL #{attachment}: #{matches.join(', ')}."
          end

          matches.first
        end

        def required_config_var(env, name)
          value = env[name]
          raise ConfigurationError, "The config var #{name} is missing or empty." unless present?(value)

          value
        end

        def present?(value)
          !value.nil? && !value.empty?
        end

        def normalize_url(url)
          url.downcase(:ascii).delete_suffix("/")
        end
      end
    end
  end
end
