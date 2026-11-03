# frozen_string_literal: true

require "json"
require "time"

module Vectors
  DIRECTORY = File.expand_path("../../../test-vectors", __dir__)

  module_function

  def load(name)
    JSON.parse(File.read(File.join(DIRECTORY, name)))["cases"]
  end

  def instant(value)
    value.nil? ? nil : Time.iso8601(value)
  end

  def test_name(name)
    "test_#{name.gsub(/[^A-Za-z0-9]+/, "_")}"
  end
end
