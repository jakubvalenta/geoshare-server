# GeoShare Server

A web server that provides API used by [GeoShare](https://github.com/jakubvalenta/geoshare).

## Features

- Geocoding via Google Maps API

## Prerequisites

- Linux x86_64 (due to [Lettuce Native Transports](https://redis.github.io/lettuce/advanced-usage/native-transports/))
- Redis
- Google developer account

## Development

### Local

Generate a JWT secret and store it in a file:

```shell
mkdir -p ./secrets
head -c 64 < /dev/urandom > ./secrets/jwt-secret
```

Generate a status check API key and store it in a file:

```shell
your_status_api_key=$(uuidgen)
echo "Your status check API key is: $your_status_api_key"
echo -n "$your_status_api_key" | sha256sum | awk '{print $1}' | tr -d '\n' > ./secrets/status-api-key-hash
```

Get your Google Maps API key in Google Cloud console and store it in the file
`./secrets/google-maps-api-key`.

Start Redis:

```shell
redis-server --port 0 --unixsocket /run/user/1000/redis.sock
```

Run the application:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
STATUS_API_KEY_HASH_FILE="secrets/status-api-key-hash" \
./gradlew run
```

Run the application while bypassing Google Maps and returning random locations
instead:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
STATUS_API_KEY_HASH_FILE="secrets/status-api-key-hash" \
./gradlew run --args='-config=application-demo.conf'
```

Check the application status:

```shell
curl -i -H "X-Api-Key: $your_status_api_key" "https://127.0.0.1:8080/v1/google-maps/status"
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
STATUS_API_KEY_HASH_FILE="path to a file with your status check api key hash" \
java -Xms128m -Xmx256m -jar "build/libs/GeoShare Server-all.jar" -port=8080
```

## License

Feel free to remix this project under the terms of the GNU General Public
License version 3 or later. See [COPYING](./COPYING) and [NOTICE](./NOTICE).

Some components are derived from third-party code under other compatible
licenses; see individual subdirectories.
