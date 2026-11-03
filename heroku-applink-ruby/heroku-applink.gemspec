# frozen_string_literal: true

require_relative "lib/heroku/applink/version"

Gem::Specification.new do |spec|
  spec.name = "heroku-applink"
  spec.version = Heroku::AppLink::VERSION
  spec.authors = ["Heroku"]
  spec.summary = "Heroku AppLink SDK for Ruby"
  spec.description = "Parse invocations from Salesforce and look up Heroku AppLink authorizations."
  spec.homepage = "https://github.com/heroku/heroku-applink-ruby"
  spec.license = "BSD-3-Clause"
  spec.required_ruby_version = ">= 3.4"

  spec.metadata = {
    "source_code_uri" => spec.homepage,
    "changelog_uri" => "#{spec.homepage}/blob/main/CHANGELOG.md",
    "rubygems_mfa_required" => "true"
  }

  spec.files = Dir["lib/**/*.rb", "README.md", "CHANGELOG.md", "LICENSE.txt"]
  spec.require_paths = ["lib"]

  # The allow_comments parse option needs json 2.20. Older versions silently ignore it and accept comments.
  spec.add_dependency "json", ">= 2.20"
  spec.add_dependency "net-http"
  spec.add_dependency "openssl"
  spec.add_dependency "time"
  spec.add_dependency "timeout"
  spec.add_dependency "uri"
end
