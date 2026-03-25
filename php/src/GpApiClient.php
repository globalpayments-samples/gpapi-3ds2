<?php
declare(strict_types=1);

/**
 * GP-API HTTP client with token caching.
 * Uses direct cURL calls — no SDK dependency.
 * Token is cached in /tmp/gpapi_token.json between requests.
 */
class GpApiClient
{
    private const BASE_URL   = 'https://apis.sandbox.globalpay.com/ucp';
    private const GP_VERSION = '2021-03-22';
    private const TOKEN_FILE = '/tmp/gpapi_token.json';
    private const REFRESH_MARGIN = 60; // regenerate if <60s to expiry

    // ── Token management ───────────────────────────────────────────────────

    public static function getAccessToken(): string
    {
        $cached = self::readTokenCache();
        if ($cached !== null) {
            return $cached;
        }
        return self::generateToken();
    }

    private static function readTokenCache(): ?string
    {
        if (!file_exists(self::TOKEN_FILE)) {
            return null;
        }
        $data = json_decode(file_get_contents(self::TOKEN_FILE), true);
        if (empty($data['token']) || empty($data['expires_at'])) {
            return null;
        }
        if (time() >= ($data['expires_at'] - self::REFRESH_MARGIN)) {
            return null;
        }
        return $data['token'];
    }

    private static function generateToken(): string
    {
        $appId  = getenv('GP_APP_ID');
        $appKey = getenv('GP_APP_KEY');

        if (!$appId || !$appKey) {
            throw new \RuntimeException('GP_APP_ID and GP_APP_KEY must be set');
        }

        $nonce  = gmdate('Y-m-d\TH:i:s') . '.' . sprintf('%03d', (int)(microtime(true) * 1000) % 1000) . 'Z';
        $secret = hash('sha512', $nonce . $appKey);

        $body = json_encode([
            'app_id'     => $appId,
            'nonce'      => $nonce,
            'secret'     => $secret,
            'grant_type' => 'client_credentials',
        ]);

        $result = self::curlRequest('POST', '/accesstoken', $body, null);

        if (empty($result['token'])) {
            throw new \RuntimeException('Token generation failed: ' . json_encode($result));
        }

        $expiresAt = time() + (int) ($result['seconds_to_expire'] ?? 3599);
        file_put_contents(self::TOKEN_FILE, json_encode([
            'token'      => $result['token'],
            'expires_at' => $expiresAt,
        ]));

        return $result['token'];
    }

    // ── HTTP helpers ───────────────────────────────────────────────────────

    /**
     * Make an authenticated request to GP-API.
     *
     * @throws \RuntimeException on HTTP error
     */
    public static function request(string $method, string $path, ?array $body = null): array
    {
        $token   = self::getAccessToken();
        $payload = $body !== null ? json_encode($body) : null;
        $result  = self::curlRequest($method, $path, $payload, $token);
        return $result;
    }

    private static function curlRequest(
        string  $method,
        string  $path,
        ?string $payload,
        ?string $token
    ): array {
        $url = self::BASE_URL . $path;

        $headers = [
            'Content-Type: application/json',
            'X-GP-Version: ' . self::GP_VERSION,
        ];
        if ($token !== null) {
            $headers[] = 'Authorization: Bearer ' . $token;
        }

        $ch = curl_init($url);
        curl_setopt_array($ch, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_CUSTOMREQUEST  => strtoupper($method),
            CURLOPT_HTTPHEADER     => $headers,
            CURLOPT_TIMEOUT        => 30,
            CURLOPT_SSL_VERIFYPEER => true,
            CURLOPT_ENCODING       => '',  // accept and decompress gzip/deflate
        ]);
        if ($payload !== null) {
            curl_setopt($ch, CURLOPT_POSTFIELDS, $payload);
        }

        $raw    = curl_exec($ch);
        $status = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        curl_close($ch);

        $data = json_decode($raw ?: '{}', true) ?? [];

        if ($status < 200 || $status >= 300) {
            $err = new \RuntimeException(
                ($data['error']['message'] ?? "GP-API error {$status}")
            );
            // Attach GP-API error data to the exception via a property
            $err->gpData = $data;
            $err->httpStatus = $status;
            throw $err;
        }

        return $data;
    }

    // ── Utility ────────────────────────────────────────────────────────────

    public static function toMinorUnits(string $amount): string
    {
        return (string) (int) round((float) $amount * 100);
    }

    public static function twoDigitYear(string $year): string
    {
        return substr($year, -2);
    }

    public static function uuid(): string
    {
        return sprintf(
            '%04x%04x-%04x-%04x-%04x-%04x%04x%04x',
            mt_rand(0, 0xffff), mt_rand(0, 0xffff),
            mt_rand(0, 0xffff),
            mt_rand(0, 0x0fff) | 0x4000,
            mt_rand(0, 0x3fff) | 0x8000,
            mt_rand(0, 0xffff), mt_rand(0, 0xffff), mt_rand(0, 0xffff)
        );
    }

    public static function jsonResponse(array $data, int $status = 200): void
    {
        http_response_code($status);
        header('Content-Type: application/json');
        header('Access-Control-Allow-Origin: *');
        echo json_encode($data);
        exit;
    }

    public static function errorResponse(\Throwable $e, int $status = 500): void
    {
        $gpData = $e->gpData ?? [];
        self::jsonResponse([
            'success'         => false,
            'error'           => $e->getMessage(),
            'gp_error_code'   => $gpData['error']['code']   ?? null,
            'gp_error_detail' => $gpData['error']['detail'] ?? null,
            'raw'             => $gpData,
        ], $e->httpStatus ?? $status);
    }
}
