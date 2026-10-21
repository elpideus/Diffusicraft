# The relief map

The relief map shows a Diffusicraft world in a web browser, shaded by its relief, so that you can find places and
their coordinates. It is served by the mod itself, for the world that is open, and shows the land before anyone has
walked there.

## Opening it

- **In singleplayer**, press the map button at the end of the pause menu's row of small icon buttons. The map opens
  in your browser, centred on you.
- **Anywhere**, run `/diffusicraft map`. The game answers with a link to click and a *Copy* button. Operators can run
  it on a server, and so can the owner of a singleplayer world without cheats: the map changes nothing in the world.

The map's web server starts with the first link and stops when the world closes; a link from an earlier session no
longer works, so ask for a new one. The button and the command are there only in worlds that Diffusicraft generates,
and only while [`reliefMap`](CONFIG.md#reliefmap) is on.

## Using it

| To | Do |
|---|---|
| Pan | Drag, or press the arrow keys or `W` `A` `S` `D` |
| Zoom | The mouse wheel, a pinch, `+` and `−`, or the buttons |
| Read a place | Hover over it: X, Z, the ground's Y and the biome show at the bottom left |
| Get its coordinates | Click it: a pin and a card with X, Y and Z, and buttons to copy `/tp @s X Y Z`, `X Y Z` or `X Z` |
| Find a biome | Type its name in the search box (`/`) and pick one: the five nearest places, with their distance and direction |
| Go to coordinates | Type `X Z` or `X Y Z` and press Enter |
| Find yourself or the spawn | `You` (`P`), `Follow` (`F`) to keep you in the middle, or type `me` or `spawn` |
| Switch dimension | The list beside the search box, when more than one dimension has Diffusicraft terrain |

The Y of the card is where you would stand: one block over the ground, or over the water. The address in the browser
keeps the dimension, the place and the zoom, so a bookmark or a reload comes back to them.

The blue arrow is you, the white dot the world spawn, the red pin the place you clicked and the red line the world
border.

### Coarse and detailed

Zoomed out, the map is drawn from the world's coarse map, the first step of the terrain, which costs next to nothing:
it shows the mountains smoothed and the coasts approximate, and the hover readout marks its heights with `≈` and
*(coarse)*. Zoomed in to about a pixel per 30 metres of the real world, the map is drawn as the world generates it,
with every river, cliff and biome: the same models run as for chunks, so a place seen for the first time takes a few
seconds on a GPU and longer on a CPU. *Drawing N tiles* at the top right counts what is still to come; the nearest
come first.

What has been drawn in detail stays sharp when you zoom out again, laid over the coarse map. The tiles are kept under
`<world>/diffusicraft/relief-map`, so the next visit is instant; they are deleted when the mod is updated, since the
terrain may change with it. Delete the folder to free the space; it is drawn again as needed.

The colours are the blocks' own map colours, as on a vanilla map, with the water darker as it deepens, and the light
comes from the north-west. Zoomed out past the terrain's own pixels, the shading exaggerates the relief, so that
mountains still show on a map of a continent.

## On a server

By default the map listens on `127.0.0.1`, so only the server's own machine can open it, and the command says so. To
let players open it:

1. Set [`reliefMapAddress`](CONFIG.md#reliefmapaddress) to `0.0.0.0` and pick a [`reliefMapPort`](CONFIG.md#reliefmapport)
   that your firewall lets through.
2. Set [`reliefMapUrl`](CONFIG.md#reliefmapurl) to the address players reach it at, such as `http://play.example.net:8100`
   or, behind a reverse proxy, `https://map.example.net`. Without it, the links use `server-ip` from
   `server.properties`, or `localhost` when that is empty.

Each player who asks gets a link of their own, with a secret in its path: anyone with the link sees the map and that
player's position, and nobody can guess it. The console's link shows the spawn and no player. The map is plain HTTP,
so put it behind a proxy with HTTPS if the links cross the internet.

Drawing detailed tiles uses the server's GPU or CPU as generating chunks does, on two threads of its own at the lowest
priority, so the game's ticks never wait for it.

## How it works

The code is in `src/main/java/.../map`, with the page in `src/main/resources/diffusicraft/map/index.html` and the
pause menu's button in the client's `screen/ReliefMapButton` and `mixin/PauseScreenMixin`.

- **`ReliefMap`** registers the command, starts the map with the first link and stops it with the server. It finds the
  levels that Diffusicraft generates and keeps the tiles on disk, except in a development environment, where they live
  in memory unless `-Ddiffusicraft.mapCache=true`.
- **`MapService`** answers the page under `/<token>/`: the page itself, `api/info` (the levels and their biomes),
  `api/state` (the player, the spawn and the tiles drawn since the page last asked), `api/point` (one column of the
  full-resolution terrain), `api/search` (places of a biome) and `tile/<level>/<zoom>/<x>/<y>`. It runs on
  `MapHttpServer`, a small HTTP/1.1 server with a virtual thread per connection, so a request can wait for its tile as
  long as the tile takes. A request whose browser gave up, because the tile scrolled out of view, is dropped before
  its tile is drawn.
- **`MapTiles`** draws, keeps and mixes the tiles of one level. Zoom level `z` draws `2^z` blocks per pixel. Levels up
  to the *fine* one, about one terrain pixel per pixel, are drawn from the full-resolution terrain; coarser levels from
  the coarse map, with the fine tiles drawn so far shrunk over them. Each fine tile bumps the level's revision, and
  `api/state` tells the page which coarser tiles to fetch again. Tiles are drawn newest request first (`LifoExecutor`):
  someone panning wants where they are now before where they were.
- **`LevelMap`** draws a tile. Fine tiles sample the terrain with `LodSampler`, the same columns that Distant Horizons
  and Voxy get. Coarse tiles interpolate the coarse map's elevation bicubically and take the colours from a coarse
  grid of columns. Both are hillshaded.
- **`MapRaster`** is a tile: 256 × 256 pixels, each with its colour, the ground's Y, the water's depth (up to 255) and
  the biome. It goes to the browser gzipped, and the browser unzips it, so the page reads the heights and biomes under
  the cursor without asking the game.

| World size | Fine zoom | Coarsest zoom | Blocks per pixel, coarsest |
|---|---|---|---|
| Vanilla | 0 | 6 | 64 |
| Medium | 1 | 7 | 128 |
| Large | 2 | 8 | 256 |
| Grand | 3 | 9 | 512 |
| Extreme | 4 | 10 | 1024 |
| Ultra | 5 | 11 | 2048 |

The coarsest zoom keeps a coarse tile within about 64 pixels of the coarse map, the most the terrain keeps in memory at
once, and the page zooms out one level further by scaling.

### Working on the page

The page is one file with its script and styles inline, as its Content Security Policy requires. To see it without
the game, serve a synthetic level with the preview test, which writes the link to the file you name and serves until
a file of the same name with `.stop` appended appears, or for 25 minutes:

```bash
./gradlew test --tests '*MapPagePreview' -Pdiffusicraft.mapPreview=/tmp/map-url
```

To check it against a real world, run `ReliefMapGameTest` (it needs the models). It asks for the player's link as the
pause menu's button does, without opening a browser, then fetches the page, the levels and the tiles around the
player, and saves their colours as `diffusicraft-map-coarse.png` and `-fine.png`. With
`-Pdiffusicraft.mapLink=<file>` it writes the link to that file and keeps the world open until the `.stop` file
appears, or for 20 minutes, so that the page can be opened on a world the models drew:

```bash
env -u WAYLAND_DISPLAY SDL_VIDEO_DRIVER=x11 xvfb-run -a -s "-screen 0 1600x900x24" ./gradlew runClientGameTest -PnoDevMods -Pdiffusicraft.gametests=ReliefMap -Pdiffusicraft.mapLink=/tmp/map-url
```
