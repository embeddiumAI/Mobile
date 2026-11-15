# eAI Mobile for Android

Android companion app for the eAI desktop assistant. The complete desktop Rust source and Cargo manifest are copied into [`desktop-source`](desktop-source/) as a reference for feature parity. The Android UI is implemented with Kotlin and Jetpack Compose, with Ollama chat/model access over the LAN.

## Build

Open this folder in Android Studio (Hedgehog or newer) and sync Gradle, or run:

```sh
gradle assembleDebug
```

Install `app/build/outputs/apk/debug/app-debug.apk` on an Android device. The Android device and Ollama host must be on the same network. In Ollama, enable LAN access on the host and set its address in eAI Mobile's connection settings (for example `http://192.168.1.20:11434`).

## Android adaptation

- Compose layouts, bottom navigation, edge-to-edge screen sizing and IME-aware prompt composer for touch use.
- Ollama model discovery and streaming chat through Android's asynchronous HTTP client.
- Conversation history persists in app-private storage as JSON.
- Desktop-only filesystem, shell, desktop-control and drag-and-drop functions remain in the copied source reference; Android uses explicit mobile-safe capabilities.

The app permits cleartext HTTP for private LAN Ollama endpoints. Use a trusted network and configure HTTPS/reverse proxy for connections beyond a trusted LAN.
