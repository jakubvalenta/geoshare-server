# GeoShare Server

A web server for the [GeoShare](https://github.com/jakubvalenta/geoshare)
Android app.

## Features

- Geocoding via Google Maps API

## Prerequisites

- Linux x86_64 (due to [Lettuce Native Transports](https://redis.github.io/lettuce/advanced-usage/native-transports/))
- Redis
- Google developer account

## Development

### Local

Go to Google Cloud console, create your Google Maps API key, and store it in the
file `./secrets/google-maps-api-key`.

Generate a JWT secret and store it in a file:

```shell
mkdir -p ./secrets
head -c 64 < /dev/urandom > ./secrets/jwt-secret
```

Generate a status check API key and store it in a file:

```shell
your_status_api_token=$(uuidgen)
echo "Your status check API token is: $your_status_api_token"
echo -n "$your_status_api_token" | sha256sum | awk '{print $1}' | tr -d '\n' > ./secrets/status-api-token-hash
```

Start Redis:

```shell
redis-server --port 0 --unixsocket /run/user/1000/redis.sock
```

Run the application:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
STATUS_API_TOKEN_HASH_FILE="secrets/status-api-token-hash" \
./gradlew run
```

Or run the application while bypassing Google Maps API and returning random
locations instead:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
DRY_RUN="true" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
STATUS_API_TOKEN_HASH_FILE="secrets/status-api-token-hash" \
./gradlew run
```

Or run the application with generous rate limiting:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
RATE_LIMIT_DEFAULT=200 \
RATE_LIMIT_LOGIN=200 \
RATE_LIMIT_REGISTER=200 \
RATE_LIMIT_UNVERIFIED=200 \
RATE_LIMIT_VERIFIED=200 \
STATUS_API_TOKEN_HASH_FILE="secrets/status-api-token-hash" \
./gradlew run
```

Finally, you can check the status of the running application:

```shell
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/cache/connection"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/challenge/success/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/login/error/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/login/success/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/register/error/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/register/success/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/auth/unauthorized/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/google-maps/connection"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/google-maps/exception/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/google-maps/success/hour"
curl -I -H "Authorization: Bearer $your_status_api_token" "https://127.0.0.1:8080/v1/status/rate-limit/hour"
```

### Deployment

Build:

```shell
./gradlew buildFatJar
```

Run:

```shell
CACHE_URI="your redis uri" \
GOOGLE_MAPS_API_KEY_FILE="path to a file with your google maps api key" \
JWT_SECRET_FILE="path to a file with your jwt secret" \
STATUS_API_TOKEN_HASH_FILE="path to a file with your status check api key hash" \
java -Xms128m -Xmx256m -jar "build/libs/GeoShare Server-all.jar" -port=8080
```

## License

Feel free to remix this project under the terms of the GNU General Public
License version 3 or later. See [COPYING](./COPYING) and [NOTICE](./NOTICE).

Some components are derived from third-party code under other compatible
licenses; see individual subdirectories.
