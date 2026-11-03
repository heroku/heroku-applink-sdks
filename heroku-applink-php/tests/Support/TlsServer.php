<?php

declare(strict_types=1);

namespace Heroku\AppLink\Tests\Support;

final class TlsServer
{
    /** @var resource */
    private $process;
    private string $directory;
    public readonly string $url;
    /** @var non-empty-string */
    public readonly string $caFile;

    public function __construct()
    {
        $this->directory = sys_get_temp_dir() . '/heroku-applink-php-' . bin2hex(random_bytes(8));
        mkdir($this->directory);
        $this->caFile = $this->directory . '/cert.pem';
        file_put_contents($this->caFile, self::selfSignedCertificate($this->directory . '/openssl.cnf'));
        $this->respond(self::respondWith(500));

        $process = proc_open(
            [PHP_BINARY, __DIR__ . '/server.php', $this->caFile, $this->specFile(), $this->logFile()],
            [1 => ['pipe', 'w'], 2 => STDERR],
            $pipes,
        );
        if ($process === false) {
            throw new \RuntimeException('Starting the test server failed.');
        }
        $this->process = $process;
        $address = trim((string) fgets($pipes[1]));
        if ($address === '') {
            throw new \RuntimeException('The test server failed to start.');
        }
        $this->url = "https://$address";
    }

    /**
     * @param array<string, string> $headers
     *
     * @return array{status: int, headers: array<string, string>, body: string, delay: float, slowBody: bool}
     */
    public static function respondWith(
        int $status,
        string $body = '',
        array $headers = [],
        float $delay = 0.0,
        bool $slowBody = false,
    ): array {
        return [
            'status' => $status,
            'headers' => $headers,
            'body' => base64_encode($body),
            'delay' => $delay,
            'slowBody' => $slowBody,
        ];
    }

    /**
     * @param array{status: int, headers: array<string, string>, body: string, delay: float, slowBody: bool} ...$responses
     */
    public function respond(array ...$responses): void
    {
        file_put_contents($this->logFile(), '');
        $spec = json_encode(['generation' => bin2hex(random_bytes(8)), 'responses' => $responses], JSON_THROW_ON_ERROR);
        file_put_contents($this->specFile() . '.tmp', $spec);
        rename($this->specFile() . '.tmp', $this->specFile());
    }

    /**
     * @return list<array{method: string, path: string, headers: array<string, string>}>
     */
    public function requests(): array
    {
        $requests = [];
        foreach (file($this->logFile(), FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES) ?: [] as $line) {
            /** @var array{method: string, path: string, headers: array<string, string>} $request */
            $request = json_decode($line, true, flags: JSON_THROW_ON_ERROR);
            $requests[] = $request;
        }
        return $requests;
    }

    public function stop(): void
    {
        proc_terminate($this->process);
        proc_close($this->process);
        foreach (glob($this->directory . '/*') ?: [] as $file) {
            unlink($file);
        }
        rmdir($this->directory);
    }

    private function specFile(): string
    {
        return $this->directory . '/spec.json';
    }

    private function logFile(): string
    {
        return $this->directory . '/requests.log';
    }

    private static function selfSignedCertificate(string $configFile): string
    {
        file_put_contents($configFile, <<<'CNF'
            [req]
            distinguished_name = dn
            [dn]
            [ext]
            basicConstraints = critical, CA:TRUE
            keyUsage = critical, digitalSignature, keyCertSign
            subjectAltName = IP:127.0.0.1, DNS:localhost
            CNF);
        $options = ['config' => $configFile, 'digest_alg' => 'sha256', 'x509_extensions' => 'ext'];
        $key = openssl_pkey_new(['private_key_type' => OPENSSL_KEYTYPE_EC, 'curve_name' => 'prime256v1']);
        if ($key === false) {
            throw new \RuntimeException('Generating the test key failed.');
        }
        $csrKey = $key;
        $csr = openssl_csr_new(['commonName' => '127.0.0.1'], $csrKey, $options);
        if (!$csr instanceof \OpenSSLCertificateSigningRequest) {
            throw new \RuntimeException('Generating the test certificate request failed.');
        }
        $certificate = openssl_csr_sign($csr, null, $key, 1, $options);
        if (
            $certificate === false
            || !openssl_x509_export($certificate, $certificatePem)
            || !openssl_pkey_export($key, $keyPem, null, $options)
            || !is_string($certificatePem)
            || !is_string($keyPem)
        ) {
            throw new \RuntimeException('Generating the test certificate failed.');
        }
        return $certificatePem . $keyPem;
    }
}
