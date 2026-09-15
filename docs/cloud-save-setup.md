# Pirat.io cloud save setup

v0.5 introduces account-bound profile sync.

## Android / Google Play Games

Set these values in CI/Gradle properties before a production build:

- `PIRATIO_PGS_WEB_CLIENT_ID` — OAuth web/server client ID used by `requestServerSideAccess`.
- `PIRATIO_PGS_APP_ID` — Play Games Services application ID from Play Console.
- `PIRATIO_API_BASE_URL` — HTTPS base URL for the Pirat.io backend, for example `https://api.example.com`.

The Android client uses Play Games Services v2. If these values are absent it safely stays in local-save mode.

## Backend

Required production environment variables:

- `DATABASE_URL` — PostgreSQL connection URL.
- `SESSION_SECRET` — long random secret used to sign Pirat.io cloud sessions.
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GOOGLE_PLAY_GAMES_APP_ID`
- `APPLE_BUNDLE_ID` — defaults to `com.gillingteknik.piratio`.

Optional:

- `GOOGLE_REDIRECT_URI` — only if the OAuth client configuration requires it.
- `PGSSL=disable` — local PostgreSQL only; production should use TLS.

## Apple / Game Center

The server endpoint `/api/auth/apple` is prepared for the GameKit identity-verification payload: player ID, bundle ID, public key URL, signature, salt and timestamp. The iOS client still needs to be created before this can be exercised end to end.

## Economy security

The prototype currently syncs coins from the client because the existing gameplay is client-authoritative. Before real-money coin packs ship, coin grants and purchase entitlements must move to server-authoritative endpoints and store receipts/tokens must be verified server-side.
