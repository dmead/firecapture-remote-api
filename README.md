# FireCapture Remote API

A FireCapture plugin that serves a local REST API (`http://127.0.0.1:1899/v1/`) for controlling FireCapture
while it runs. The server starts when FireCapture loads its plugins, so it doesn't need to be selected in the
pre-processing dropdown.

Built and tested against **FireCapture v2.7 build 15** (Linux x64) using its dummy camera. Not yet tested with real
hardware.

## Endpoints (v1)

`GET /v1` returns this list as JSON. Device endpoints act on whatever FireCapture itself has connected (set up in
FireCapture's settings); if a device isn't configured they return **503** with a message saying so.

| Method | Path | Does |
|---|---|---|
| GET | `/v1/status` | FireCapture version + compatibility, camera, profile, filter, recording, which devices are configured |
| **Camera** | | |
| GET | `/v1/camera` | name, colour, temperature, sensor size, pixel size, ROI, gain/exposure with ranges, timebase |
| PUT | `/v1/camera` | `{gain, exposureMs, roi:[x,y,w,h]}` (any subset), applied through the GUI controls; `warnings` if a value didn't take |
| GET | `/v1/camera/controls` | every control the camera supports (Gamma, WRed/WBlue, USBTraffic, HighSpeed, Cooler, TargetTemp, AntiDew, ...): value, min, max, enabled, display |
| PUT | `/v1/camera/controls` | `{"Gamma": 50}` or `{"Gamma": {"value": 50, "enabled": true}}`; validated as a batch before anything changes |
| POST | `/v1/camera/roi/center` | centre the ROI |
| GET | `/v1/camera/frame` | current frame as PNG |
| **Capture** | | |
| GET | `/v1/capture` | active/paused, file, frames, fps, limit, file type |
| PUT | `/v1/capture` | `{limit: "180s"\|"3m"\|"5000f"\|"500mb", fileType: "SER"}`; the limit belongs to the current filter slot |
| POST | `/v1/capture/start` `/stop` `/pause` `/resume` | recording control (409 on the wrong state) |
| GET / PUT | `/v1/profiles`, `/v1/profile {name}` | list / switch capture profile (name or short name) |
| GET / PUT | `/v1/filters`, `/v1/filter {name}` | list / switch filter slot, including its per-filter camera settings; moves the wheel if configured |
| **Mount** (FireCapture's telescope/autoguider link: ASCOM, INDI, ST-4 via camera, Shoestring) | | |
| GET | `/v1/mount` | interface, status, canMoveAxis, canPark, autoguiding, guide error, target spot, slew rates |
| POST | `/v1/mount/pulse` | `{direction: north\|south\|east\|west, ms}` guide pulse (≤ 10 s) |
| POST | `/v1/mount/move` | `{direction, ms, rate?}` slew one axis (≤ 60 s; default rate = FireCapture's) |
| POST | `/v1/mount/stop` | stop all axes and restore tracking |
| POST | `/v1/mount/park` | park (409 if unsupported) |
| POST | `/v1/mount/autoguide` | `{enabled}` FireCapture's planetary autoguiding |
| **Focuser** | | |
| GET | `/v1/focuser` | connected, position, max, step size, temperature, temp-comp, capabilities |
| POST | `/v1/focuser/move` | `{position}` absolute or `{by}` relative |
| PUT | `/v1/focuser` | `{connected, tempComp}` |
| **Filter wheel / rotator** | | |
| GET / PUT | `/v1/filterwheel` | type, position, moving / `{position, connected}` (wheel only; use `/v1/filter` to switch settings too) |
| GET / POST / PUT | `/v1/rotator`, `/v1/rotator/move`, `/v1/rotator` | position, target, moving / `{angle}` or `{by}` degrees / `{connected}` |

| **Target & guide overlay** | | |
| GET | `/v1/target` | bright target (planet/Sun/Moon) in the current frame: centre, radius, bbox, edge contact, sensor position |
| GET | `/v1/overlay` | markers currently drawn, each with `inView` |
| POST | `/v1/overlay` | draw "the target should end up here" on the live view (below) |
| DELETE | `/v1/overlay` | remove all markers, or `?id=N` |

### Guide overlay (polar / drift alignment)

`POST /v1/overlay` draws a marker on FireCapture's live view showing where the target should end up, e.g. after a
drift check says "turn the azimuth knob until Saturn moves left by this much". The marker stays pixel-aligned at
any zoom because it uses FireCapture's own preview scaling. If the spot is outside the visible view, an arrow at
the edge points toward it, labelled with the distance in frame px and in multiples of the view size.

| Field | Meaning |
|---|---|
| `dx`, `dy` | position relative to the detected target, in frame px (`-300, 0` = "300 px left of where it is now"); also draws a dashed path from the current position (`"path": false` to hide it) |
| `x`, `y` | or an absolute position in frame px; `"coords": "sensor"` for sensor px |
| `shape` | `planet` (default, alias `circle`), `saturn` (globe + rings, with `angleDeg` ring tilt on screen and `ringRatio` = minor/major), `sun`, `moon`, `cross` |
| `radius` | frame px; default = the detected target's radius, so the outline matches the real disc |
| `label`, `color` | text under the marker; `green` (default), `red`, `yellow`, `cyan`, `magenta`, `orange`, `white` or `#rrggbb` |
| `ttlS` | remove automatically after this many seconds |

```bash
curl -s localhost:1899/v1/target                                     # where is it now?
curl -s -X POST -d '{"shape":"saturn","dx":-300,"dy":0,"angleDeg":43,"ringRatio":0.12,"label":"turn azimuth until Saturn sits here"}' localhost:1899/v1/overlay
curl -s -X DELETE localhost:1899/v1/overlay                          # clear when done
```

Errors are JSON `{"error": "...", "status": N}`. **501** means the running FireCapture version is missing an internal
method the plugin needs. Motion commands (mount, focuser, wheel, rotator) return as soon as they're issued; poll
the GET endpoint for progress.

```bash
curl -s localhost:1899/v1 | python3 -m json.tool          # endpoint list
curl -s -X PUT -d '{"gain": 600, "exposureMs": 5.9}' localhost:1899/v1/camera
curl -s -X PUT -d '{"limit": "3m", "fileType": "SER"}' localhost:1899/v1/capture
curl -s -X POST localhost:1899/v1/capture/start
curl -s -X POST -d '{"direction": "north", "ms": 300}' localhost:1899/v1/mount/pulse
curl -s localhost:1899/v1/camera/frame -o frame.png
```

## Build / install

Needs a JDK ≥ 11 (`javac`). It compiles against the installed FireCapture (`FC_HOME`, default `/opt/FireCapture_v2.7`)
and never copies FireCapture's jars into this project or the plugin jar. The build fails if any FireCapture class ends
up in the output.

```bash
./build.sh          # -> build/RemoteApi.jar
./install.sh        # -> $FC_HOME/plugins/x64/RemoteApi/, then restart FireCapture
./run-dummy.sh      # FireCapture with its built-in dummy camera, for testing without hardware
```

`run-dummy.sh` can change `~/FireCapture/FireCapture.ini` and the active profile on exit (file type, panel layout,
capture counter). Back them up before testing.

Port: `~/FireCapture/remoteapi.properties` → `port=1899`. Listens on loopback only.
Uninstall: `rm -r $FC_HOME/plugins/x64/RemoteApi`.

## How it works

- `RemoteApiPlugin` implements FireCapture's published plugin interface (`IFilter`, Plugin-1.1). It does no image
  processing; its constructor starts the HTTP server.
- `Api` calls FireCapture's internal classes (`FireCapture.INSTANCE`, `CameraPanel`, `CamWorker`, `Capture`,
  `SystemSettings`). Anything that changes widgets runs on the Swing EDT, and settings go through the same controls the
  GUI uses, so the window stays in sync.
- FireCapture's version is read reflectively at runtime, not from compile-time constants.
- `Server` is a minimal HTTP/1.1 server on plain sockets, because the bundled JRE 12 has no `jdk.httpserver`.

## Test status / known gaps

Tested on the dummy camera (2026-10-15, which shows a Saturn image): target detection, guide overlay (in-view
markers at 334% zoom land on the computed pixel; off-view edge arrows in several directions; expiry; delete by id), status, camera get/put (gain, exposure), the controls list and its
validation, frame, capture limit (a 10 s recording stopped on its own), file type, start/stop/pause/resume, profile
and filter switching, all error paths.

Not yet tested:
- **Camera controls other than gain/exposure.** The dummy camera ignores values like WRed; the endpoint warns when
  a value doesn't read back. Verify on the ASI585MC.
- **ROI change.** The dummy sensor is fixed; verify on the ASI585MC.
- **Mount, filter wheel, rotator.** None are configured in FireCapture here. Code follows FireCapture's interfaces
  but has never been run against hardware.
- **Focuser.** FireCapture's INDI focuser was present but the device was unplugged; moves are refused when it
  reports no capabilities. Verify moves with the EAF connected.

Still to do: events stream (SSE) for capture start/stop/frames; raw 8/16-bit frame endpoint; device connect/setup
(currently done in FireCapture's settings dialogs); still-image snapshot (FireCapture's `forceSnapshot` is the
ring-buffer "emergency snapshot", so it isn't exposed).

## License note

FireCapture is closed-source freeware (© Torsten Edelmann). Its license forbids redistribution, alteration and
reverse engineering. This plugin is for private use. Don't distribute it bundled with FireCapture or copy any
FireCapture code or jars into this repository.
