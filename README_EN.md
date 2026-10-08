# OrbitCam

[简体中文](README.md) · [English](README_EN.md)

OrbitCam is a client-side Minecraft Fabric mod that adds a modeling-tool style orbit camera.
Drag to pan, orbit around a pivot and wheel to dolly, while the crosshair is freed to move
anywhere on screen — so you can interact with any block you can see without turning the
camera — plus a hold-to-zoom magnifier for precise work.

## Keys

| Key                  | Default         | Note                                      |
| -------------------- | --------------- | ----------------------------------------- |
| Enter/exit orbit     | **F6**          | the only entry point                      |
| Modifier             | **Left Alt**    | role depends on the mode, see below       |
| Rotate               | **Left mouse**  | drag to orbit the camera around the pivot |
| Pan                  | **Right mouse** | drag to move the camera in its own plane  |
| Zoom                 | **Wheel**       | change the orbit distance                 |
| Magnifier            | **C**           | zoom in on the pointer; wheel adjusts it  |
| WASD / Space / Shift | —               | free flight, hold Ctrl to sprint          |

## Modifier modes

| Mode        | Modifier        | Mouse buttons                                                                          |
| ----------- | --------------- | -------------------------------------------------------------------------------------- |
| Hold        | hold to switch  | LMB rotate, RMB pan, wheel zoom                                                        |
| Toggle      | press to switch | same as above                                                                          |
| Independent | wheel only      | rotate/pan run alongside break/place when bound to keys that do not clash with LMB/RMB |
| **Hybrid**  | wheel only      | click (on release) = break/place, drag = rotate/pan                                    |

## Config

- Feel: rotation / pan sensitivity and smoothing, scale sensitivity, orbit distance
- Zoom: zoom factor, zoom sensitivity, transition speed, anchor lock
- Flight: horizontal / vertical speed, horizontal / vertical multiplier, accel time, decel time
- Mode: control mode, drag threshold
- Rendering: pivot block, outline colour
- Reach: block reach, entity reach
- Singleplayer: reach sync, body follows camera
- HUD: status HUD, X / Y

## Build

```powershell
.\gradlew.bat build                 # build all three versions
.\gradlew.bat :v26_2:runClient      # run one version
```
