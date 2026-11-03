# frozen_string_literal: true

require "openssl"
require "socket"

class TlsServer
  Request = Data.define(:http_method, :path, :headers)

  attr_reader :url, :cert_store

  def self.respond_with(status, body = "", headers = {})
    lambda do |socket|
      socket.write("HTTP/1.1 #{status} Status\r\nConnection: close\r\nContent-Length: #{body.bytesize}\r\n")
      headers.each { |name, value| socket.write("#{name}: #{value}\r\n") }
      socket.write("\r\n")
      socket.write(body)
    end
  end

  def initialize
    key = OpenSSL::PKey::EC.generate("prime256v1")
    certificate = self_signed_certificate(key)
    @cert_store = OpenSSL::X509::Store.new.tap { |store| store.add_cert(certificate) }
    context = OpenSSL::SSL::SSLContext.new.tap do |ssl|
      ssl.cert = certificate
      ssl.key = key
    end
    @tcp_server = TCPServer.new("127.0.0.1", 0)
    @url = "https://127.0.0.1:#{@tcp_server.addr[1]}"
    @mutex = Mutex.new
    respond(self.class.respond_with(500))
    @thread = Thread.new { accept_loop(context) }
  end

  def respond(*handlers)
    @mutex.synchronize do
      @handlers = handlers
      @requests = []
    end
  end

  def requests
    @mutex.synchronize { @requests.dup }
  end

  def stop
    @thread.kill
    @tcp_server.close
  end

  private

  def accept_loop(context)
    loop do
      tcp = @tcp_server.accept
      Thread.new { handle(OpenSSL::SSL::SSLSocket.new(tcp, context)) }
    end
  end

  def handle(socket)
    socket.sync_close = true
    socket.accept
    request_line = socket.gets("\r\n")
    return if request_line.nil?

    method, path = request_line.split
    headers = {}
    while (line = socket.gets("\r\n")) && line != "\r\n"
      name, value = line.split(":", 2)
      headers[name.downcase] = value.strip
    end
    handler = @mutex.synchronize do
      @requests << Request.new(http_method: method, path: path, headers: headers)
      @handlers[[@requests.size - 1, @handlers.size - 1].min]
    end
    handler.call(socket)
  rescue OpenSSL::SSL::SSLError, SystemCallError, IOError
    nil
  ensure
    socket.close
  end

  def self_signed_certificate(key)
    certificate = OpenSSL::X509::Certificate.new
    certificate.version = 2
    certificate.serial = 1
    certificate.subject = certificate.issuer = OpenSSL::X509::Name.parse("/CN=127.0.0.1")
    certificate.public_key = key
    certificate.not_before = Time.now - 60
    certificate.not_after = Time.now + 3600
    extensions = OpenSSL::X509::ExtensionFactory.new(certificate, certificate)
    certificate.add_extension(extensions.create_extension("basicConstraints", "CA:TRUE", true))
    certificate.add_extension(extensions.create_extension("keyUsage", "digitalSignature,keyCertSign", true))
    certificate.add_extension(extensions.create_extension("subjectAltName", "IP:127.0.0.1"))
    certificate.sign(key, OpenSSL::Digest.new("SHA256"))
  end
end
