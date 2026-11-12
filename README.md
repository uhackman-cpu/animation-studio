# Animation Studio

An Android-first, multi-style 2D animation editor prototype.

## Current foundation

- Landscape editor shell for a 16:9 / 1920×1080 production canvas.
- OpenGL ES 2.0 canvas surface (runs on the tested OpenGL ES 3.2 device).
- Initial editor toolbar and timeline placeholders.
- GitHub Actions workflow builds a debug APK on pushes to `main`, pull requests, and manual dispatch.

## Build

The workflow uses JDK 17, Android SDK platform 35, and Gradle 8.9.

Download the `animation-studio-debug-apk` artifact from the latest successful **Android APK** workflow run.

## Roadmap

1. Canvas coordinate system and 16:9 stage viewport.
2. Drawing tools, layers, undo/redo, and SVG import.
3. Timeline, frame exposure, keyframes, and playback.
4. Character parts, rigging, and camera animation.
5. Effects, audio, and MP4 export at 1920×1080.

## Status

Prototype foundation only. Drawing, animation editing, project persistence, and video export are not implemented yet.
