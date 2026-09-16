# Pirat.io production account, wallet and Google Play setup

The codebase is prepared for server-authoritative profiles and Google Play consumable coin purchases. The remaining production work is configuration in accounts that must be owned by the publisher.

## 1. Google Play app identity

Use Android package name:

`com.gillingteknik.piratio`

Create/configure the app in Google Play Console and publish at least an Internal testing build before testing real Play Billing flows.

Configure Play Games Services v2 for the same app. Create/link:

- the Android credential for `com.gillingteknik.piratio` with the SHA-1 certificate fingerprint used by the Play-distributed build;
- a Web OAuth client used for `requestServerSideAccess()`;
- the Play Games application ID.

The Android build consumes these values:

- `PIRATIO_PGS_WEB_CLIENT_ID`
- `PIRATIO_PGS_APP_ID`
- `PIRATIO_API_BASE_URL`
- `PIRATIO_INTEGRITY_PROJECT_NUMBER`

A debug sideload can use a separately registered debug certificate for account-flow development. Production Play Integrity enforcement is intended for Play-recognized/licensed builds.

## 2. Play Integrity

Link Play Integrity to the Google Cloud project used by the app and enable the Play Integrity API. Give the backend service account permission to call the Play Integrity API.

Production backend should set:

- `REQUIRE_PLAY_INTEGRITY=true`
- `ANDROID_PACKAGE_NAME=com.gillingteknik.piratio`
- `GOOGLE_SERVICE_ACCOUNT_JSON=<service-account-json-secret>`

On Google sign-in the Android client obtains a Standard Integrity token bound to the Play Games server auth code. The backend decodes the token through Google, verifies the request hash and (in production) requires a Play-recognized app, a licensed account and device integrity before accepting the account login.

## 3. Coin products

Create three one-time in-app products in Play Console with these exact product IDs:

- `piratio_coins_1000`
- `piratio_coins_3000`
- `piratio_coins_8000`

They are consumables. Do not encode the coin amount from the client request. `server/googlePlaySecurity.js` owns the product-to-coin mapping.

Purchase flow:

1. Android launches Google Play Billing using the logged-in Pirat.io account ID as `obfuscatedExternalAccountId`.
2. Android sends only `productId` and `purchaseToken` to `/api/shop/google-purchase`.
3. Backend reads the purchase directly from the Google Play Developer API.
4. Backend requires PURCHASED state, matching product ID and matching obfuscated account ID.
5. Backend writes an idempotent wallet-ledger credit.
6. Backend consumes the verified purchase token through Google Play.
7. The authoritative profile balance is returned to Android.

Local SharedPreferences are a cache/practice profile only and cannot submit an account coin balance to the server.

## 4. Google Play Developer API service account

Create a Google Cloud service account for the backend and link/grant it the minimum Google Play Console/API permissions required to read/consume in-app purchases. Store the JSON credential only as a deployment secret.

Never commit the real service-account JSON, OAuth client secret, database password, session secret or signing keystore.

## 5. Backend deployment

The backend is container-ready via `server/Dockerfile` and requires Node 22+ plus PostgreSQL.

Required production environment:

- `NODE_ENV=production`
- `DATABASE_URL`
- `SESSION_SECRET` (long random secret, persistent across deploys)
- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`
- `GOOGLE_PLAY_GAMES_APP_ID`
- `GOOGLE_SERVICE_ACCOUNT_JSON`
- `ANDROID_PACKAGE_NAME=com.gillingteknik.piratio`
- `REQUIRE_PLAY_INTEGRITY=true`
- `APPLE_BUNDLE_ID=com.gillingteknik.piratio`

Optional:

- `GOOGLE_REDIRECT_URI`
- `PGSSL=disable` only for a trusted local database; production should use TLS.

Health endpoint: `GET /health`

The server refuses to start in production without `DATABASE_URL` and `SESSION_SECRET`.

## 6. Database authority

PostgreSQL contains:

- account profile/profile preferences;
- authoritative coin balance;
- owned/equipped skins;
- append-only/idempotent wallet ledger.

Client requests cannot write `coins`, `skins`, combat rewards, XP or other authoritative progression fields through the profile API.

## 7. Apple/iOS

The backend already has an Apple Game Center verification endpoint. A future native iOS client should fetch Game Center's identity verification signature and call `/api/auth/apple`.

Google Play IDs and Apple Game Center IDs are separate provider identities. Cross-platform progression will require an explicit account-linking flow rather than assuming the two platform accounts belong to the same person.

## 8. Release signing

Do not modify an APK after Gradle signs it. The Android CI gate now runs `zipalign`, `apksigner verify` and ZIP integrity checks before an APK artifact is uploaded.

For Google Play distribution, use Play App Signing and preserve the upload key securely. The final Play Games Android credential must contain the certificate fingerprint actually used for the distributed app.
