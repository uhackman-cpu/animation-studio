# Animation Studio

An Android-first, GPU-backed 2D animation editor with a built-in HTTP and MCP control interface.

## Current features

- Landscape 16:9 editor with a 1920x1080 project canvas.
- OpenGL ES canvas background plus a drawable stage overlay.
- Draw rectangles and lines with touch gestures.
- Add text objects, ellipses and layers from the editor toolbar.
- Timeline playback, frame stepping, keyframes, undo and redo.
- Save and load project JSON files inside the app.
- Embedded HTTP API and MCP-compatible JSON-RPC endpoint on port 8765.
- MCP tools for project, layer, object, keyframe, playback, undo/redo and canvas screenshot control.

## Connect from Termux

Open the app first. The embedded server listens on port 8765.

On the same phone:
- Health: http://127.0.0.1:8765/health
- Editor state: http://127.0.0.1:8765/api/v1/state
- MCP endpoint: http://127.0.0.1:8765/mcp

The MCP endpoint accepts JSON-RPC initialize, tools/list and tools/call requests over HTTP POST. A Termux MCP host can register this endpoint as an HTTP MCP server, or call the REST endpoints directly.

## HTTP API examples

Read state: GET /api/v1/state

Create a project: POST /api/v1/create_project

```json
{"name":"My Animation","width":1920,"height":1080,"fps":30,"durationFrames":450}
```

Add a layer: POST /api/v1/add_layer

```json
{"name":"Foreground"}
```

Add a rectangle: POST /api/v1/add_shape

```json
{"type":"rect","x":240,"y":180,"width":420,"height":260,"color":"#FF6B35"}
```

Add a keyframe: POST /api/v1/add_keyframe

```json
{"objectId":"shape-id","property":"x","frame":30,"value":900}
```

Save project: POST /api/v1/save_project

```json
{"name":"My Animation"}
```

## MCP tools

get_editor_state, create_project, save_project, load_project, add_layer, update_layer, delete_layer, add_shape, update_shape, delete_shape, add_keyframe, set_frame, set_tool, play, stop, undo, redo, get_screenshot.

## Build

The workflow uses JDK 17, Android SDK platform 35, and Gradle 8.9. Pushes to main build a debug APK and upload the animation-studio-debug-apk artifact.

## Known scope

This is the first functional control/API slice. Keyframes currently interpolate numeric object properties linearly. The project model is JSON-backed; audio, character rigging, advanced brush engine and MP4 export remain future modules.
