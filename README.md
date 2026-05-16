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

Generate a JWT secret:

```shell
mkdir -p ./secrets
head -c 64 < /dev/urandom > ./secrets/jwt-secret
```

Save your Google Maps API key in the file `./secrets/google-maps-api-key`.

Start Redis:

```shell
redis-server --port 0 --unixsocket /run/user/1000/redis.sock
```

Run the application:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
JWT_SECRET_FILE="secrets/jwt-secret" \
./gradlew run
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
java -Xms128m -Xmx256m -jar "build/libs/GeoShare Server-all.jar" -port=8080
```

## License

Feel free to remix this project under the terms of the GNU General Public
License version 3 or later. See [COPYING](./COPYING) and [NOTICE](./NOTICE).
