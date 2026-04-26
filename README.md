# Earth World Mod

Fabric mod that adds an `Earth` world preset and a `/geotp` command.

## Features

- `Earth` world preset for world creation.
- Real-world terrain sampling from Mapzen/AWS Terrarium elevation tiles.
- Default scale of `1 block = 25 meters`, configurable in `config/earthmod.json`.
- Sea level fixed at `y=0`.
- `/geotp <lat lon>` or `/geotp <city name>` teleports to the matching Earth position in the overworld.

## Terrain And Geocoding Sources

- Terrain: Terrarium elevation tiles hosted through AWS open data.
- Geocoding: OpenStreetMap Nominatim search API.

## Config

The mod writes `config/earthmod.json` on first launch:

```json
{
  "metersPerBlock": 25.0,
  "seaLevel": 0,
  "minY": -512,
  "worldHeight": 1024,
  "terrainZoom": 13,
  "nominatimEndpoint": "https://nominatim.openstreetmap.org/search"
}
```

Changing `metersPerBlock` changes both horizontal and vertical scaling.

## Build

```bash
GRADLE_USER_HOME=$PWD/.gradle-home ./gradlew build
```

The built jar is written under `build/libs/`.
