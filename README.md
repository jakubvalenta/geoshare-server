# GeoShare Server

A web server that provides API used by [GeoShare](https://github.com/jakubvalenta/geoshare).

## Features

- Geocoding via Google Maps API

## Prerequisites

- Linux x86_64 (due to [Lettuce Native Transports](https://redis.github.io/lettuce/advanced-usage/native-transports/))
- PostgreSQL
- Redis
- Google developer account

## Development

### Setup

Start Redis:

```shell
redis-server --port 0 --unixsocket /run/user/1000/redis.sock
```

Start PostgreSQL:

```shell
systemctl start postgresql.service
```

Create PostgreSQL user and database:

```shell
sudo -u postgres createuser --createdb geoshare-server
sudo -u postgres createdb --encoding=UTF8 --template=template0 -O geoshare-server geoshare-server
```

Store your Google Maps API key in a file:

```shell
mkdir -p secrets
echo -n "your google maps api key" > secrets/google-maps-api-key
```

Run the application:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
DATABASE_URL="jdbc:postgresql:geoshare-server?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/run/postgresql/.s.PGSQL.5432" \
DATABASE_USER="geoshare-server" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
./gradlew run
```

Generate an API key:

```shell
API_KEY=$(uuidgen)
```

Store the generated API key somewhere secure such as your system keyring.

Hash the generated API key and save it to the database:

```shell
API_KEY_HASH=$(echo -n "$API_KEY" | sha256sum | awk '{print $1}') \
sudo -u postgres psql geoshare-server -c "INSERT INTO api_keys(id, name, key_hash, created_at) VALUES (GEN_RANDOM_UUID(), 'main', '$API_KEY_HASH', (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint);"
```

Make a request to the server authenticated by the generated API key:

```shell
curl -i -H "X-Api-Key: $API_KEY" http://localhost:8080/google-maps/geocode/places/ChIJgUbEo8cfqokR5lP9_Wh_DaM
```

### Run

Run the application:

```shell
CACHE_URI="redis-socket:///run/user/1000/redis.sock" \
DATABASE_URL="jdbc:postgresql:geoshare-server?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory\$FactoryArg&socketFactoryArg=/run/postgresql/.s.PGSQL.5432" \
DATABASE_USER="geoshare-server" \
GOOGLE_MAPS_API_KEY_FILE="secrets/google-maps-api-key" \
./gradlew run
```

## Deployment

Build:

```shell
./gradlew buildFatJar
```

Run:

```shell
CACHE_URI="your redis uri" \
DATABASE_URL="your postgres url" \
DATABASE_USER="your postgres user" \  # Optional
DATABASE_PASSWORD="your postgres password" \  # Optional
GOOGLE_MAPS_API_KEY_FILE="path to a file with your google maps api key" \
java -Xms128m -Xmx256m -jar "build/libs/GeoShare Server-all.jar" -port=8080
```

## License

Feel free to remix this project under the terms of the GNU General Public
License version 3 or later. See [COPYING](./COPYING) and [NOTICE](./NOTICE).
