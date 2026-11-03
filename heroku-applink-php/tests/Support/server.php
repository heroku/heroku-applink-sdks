<?php

declare(strict_types=1);

/** @var list<string> $argv */
[, $certFile, $specFile, $logFile] = $argv;

$context = stream_context_create(['ssl' => ['local_cert' => $certFile]]);
$server = stream_socket_server('tcp://127.0.0.1:0', $errorCode, $errorMessage, STREAM_SERVER_BIND | STREAM_SERVER_LISTEN, $context);
if ($server === false) {
    fwrite(STDERR, "$errorMessage\n");
    exit(1);
}
echo stream_socket_get_name($server, false), "\n";
fclose(STDOUT);

pcntl_signal(SIGCHLD, SIG_IGN);

$generation = null;
$count = 0;
while (true) {
    $connection = @stream_socket_accept($server, -1);
    if ($connection === false) {
        continue;
    }
    /** @var array{generation: string, responses: non-empty-list<array{status: int, headers: array<string, string>, body: string, delay: float, slowBody: bool}>} $spec */
    $spec = json_decode((string) file_get_contents($specFile), true, flags: JSON_THROW_ON_ERROR);
    if ($spec['generation'] !== $generation) {
        $generation = $spec['generation'];
        $count = 0;
    }
    $responses = $spec['responses'];
    $response = $responses[min($count, count($responses) - 1)];
    $count++;

    if (pcntl_fork() === 0) {
        fclose($server);
        handle($connection, $response, $logFile);
        exit(0);
    }
    fclose($connection);
}

/**
 * @param resource $connection
 * @param array{status: int, headers: array<string, string>, body: string, delay: float, slowBody: bool} $response
 */
function handle($connection, array $response, string $logFile): void
{
    if (@stream_socket_enable_crypto($connection, true, STREAM_CRYPTO_METHOD_TLS_SERVER) !== true) {
        return;
    }
    $head = '';
    while (!str_contains($head, "\r\n\r\n")) {
        $chunk = fread($connection, 8192);
        if ($chunk === false || $chunk === '') {
            return;
        }
        $head .= $chunk;
    }
    $lines = explode("\r\n", substr($head, 0, (int) strpos($head, "\r\n\r\n")));
    [$method, $path] = explode(' ', (string) array_shift($lines));
    $headers = [];
    foreach ($lines as $line) {
        [$name, $value] = explode(':', $line, 2);
        $headers[strtolower($name)] = trim($value);
    }

    $log = fopen($logFile, 'a');
    if ($log === false) {
        return;
    }
    flock($log, LOCK_EX);
    fwrite($log, json_encode(['method' => $method, 'path' => $path, 'headers' => $headers]) . "\n");
    flock($log, LOCK_UN);
    fclose($log);

    usleep((int) ($response['delay'] * 1_000_000));
    $body = base64_decode($response['body']);
    $out = "HTTP/1.1 {$response['status']} Status\r\nConnection: close\r\n";
    foreach ($response['headers'] + ['Content-Length' => (string) strlen($body)] as $name => $value) {
        $out .= "$name: $value\r\n";
    }
    @fwrite($connection, "$out\r\n");
    if ($response['slowBody']) {
        foreach (str_split($body) as $byte) {
            @fwrite($connection, $byte);
            usleep(100_000);
        }
    } else {
        for ($offset = 0; $offset < strlen($body); $offset += $written) {
            $written = @fwrite($connection, substr($body, $offset, 65536));
            if ($written === false || $written === 0) {
                break;
            }
        }
    }
    fclose($connection);
}
