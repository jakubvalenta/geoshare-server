# GeoShare Server

A web server that provides API used by [GeoShare](https://github.com/jakubvalenta/geoshare).

## Features

- Geocoding

## Development

### Local

Run:

```shell
./gradlew run
```

If the server starts successfully, you'll see the following output:

```
2024-12-04 14:32:45.584 [main] INFO  Application - Application started in 0.303 seconds.
2024-12-04 14:32:45.682 [main] INFO  Application - Responding at http://0.0.0.0:8080
```

### Production

Build:

```shell
./gradlew buildFatJar
```

Run:

```shell
java -Xms128m -Xmx256m -jar "build/libs/GeoShare Server-all.jar" -port=8080
```

## License

Feel free to remix this project under the terms of the GNU General Public
License version 3 or later. See [COPYING](./COPYING) and [NOTICE](./NOTICE).
