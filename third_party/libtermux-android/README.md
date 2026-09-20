<p align="center">
  <img src="docs/readme-banner-v2.svg" alt="libtermux-android" width="100%" />
</p>
<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android%2024%2B-green?style=flat-square&logo=android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-blue?style=flat-square&logo=kotlin" />
  <img src="https://img.shields.io/badge/Architecture-arm64%20%7C%20x86__64%20%7C%20arm%20%7C%20x86-orange?style=flat-square" />
  <img src="https://img.shields.io/badge/License-Apache%202.0-lightgrey?style=flat-square" />
  <img src="https://img.shields.io/badge/JitPack-1.0.0-red?style=flat-square" />
</p>

<h1 align="center">📦 libtermux-android</h1>
<p align="center"><b>World's first Standalone Termux SDK for Android.</b><br/>
Embed a full Linux environment inside any Android app — no Termux installation required.</p>

---

## 🚀 What is this?

**libtermux-android** is a high-performance Android library that bootstraps a complete, isolated Linux
environment (Termux runtime) inside your app's private storage. Your users get all the power of a
Linux terminal without ever knowing it exists.

```
Your App → LibTermux SDK → Isolated Linux Env → bash, python, node, ruby, pkg install...
```
> **Note:This project is currently in an experimental stage, may contain bugs, and is not usable in production at the moment. However, we are actively working on it to make it production-ready.**

## ✨ Features

| Feature | Description |
|---|---|
| **Standalone Mode** | No Termux app required — bootstrap is auto-installed |
| **Kotlin DSL API** | Clean, idiomatic Kotlin interface |
| **Java Compatible** | Full Java Builder pattern support |
| **Multi-language** | Python · Node.js · Bash · Ruby · Perl + any pkg |
| **Package Manager** | `pkg install`, `pip install`, `npm install -g` |
| **Streaming Output** | Real-time `Flow<OutputLine>` from running processes |
| **Background Service** | Keep scripts running when app is minimized |
| **TerminalView** | Drop-in terminal UI widget |
| **JNI PTY Layer** | Full pseudo-terminal support for interactive programs |
| **Multi-arch** | arm64-v8a · x86_64 · armeabi-v7a · x86 |

---

## 📦 Installation

### Gradle (JitPack)

```kotlin
// settings.gradle.kts
repositories {
    maven { url = uri("https://jitpack.io") }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.libtermux:libtermux-android:1.0.0")

    // Optional: terminal UI widget
    implementation("com.github.libtermux:terminal-view:1.0.0")
}
```

### Maven

```xml
<repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
</repository>

<dependency>
    <groupId>com.github.libtermux</groupId>
    <artifactId>libtermux-android</artifactId>
    <version>1.0.0</version>
</dependency>
```

---

## ⚡ Quick Start

### Kotlin

```kotlin
class MyActivity : AppCompatActivity() {

    private val termux by lazy {
        LibTermux.create(this) {
            autoInstall  = true
            logLevel     = LogLevel.DEBUG
            env("MY_APP", "libtermux")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // 1. Initialize (downloads bootstrap on first run ~30MB)
            termux.initialize().collect { state ->
                when (state) {
                    is InstallState.Downloading -> showProgress(state.progress)
                    is InstallState.Completed   -> onReady()
                    is InstallState.Failed      -> showError(state.error)
                    else -> {}
                }
            }
        }
    }

    private suspend fun onReady() {
        val bridge = termux.bridge

        // Run any shell command
        val result = bridge.run("uname -a")
        println(result.stdout)

        // Run Python
        val py = bridge.python("""
            import json, sys
            print(json.dumps({"python": sys.version, "status": "works!"}))
        """)
        println(py.stdout)

        // Install packages
        bridge.install("git", "curl", "wget")

        // Stream real-time output
        bridge.runStreaming("ping -c 5 8.8.8.8").collect { line ->
            when (line) {
                is OutputLine.Stdout -> updateUI(line.text)
                is OutputLine.Stderr -> showError(line.text)
                is OutputLine.Exit   -> println("done: ${line.code}")
            }
        }
    }
}
```

### Java

```java
LibTermux termux = LibTermux.builder(this)
    .autoInstall(true)
    .logLevel(LogLevel.DEBUG)
    .build();

// Blocking init (run on background thread!)
new Thread(() -> {
    try {
        termux.initializeBlocking();
        String output = termux.getBridge().runOrThrow("echo Hello Linux!");
        Log.d("TAG", output); // Hello Linux!
    } catch (Exception e) {
        Log.e("TAG", "Failed", e);
    }
}).start();
```

---

## 🔧 Advanced Usage

### Background Execution

```kotlin
// Start background service
TermuxBackgroundService.start(context, config)

// Run a long-running server in background
TermuxBackgroundService.runCommand(context, "node-server", "node server.js")
```

### Terminal UI Widget

```xml
<!-- activity_main.xml -->
<com.libtermux.view.TerminalView
    android:id="@+id/terminal"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

```kotlin
val session = termux.sessions.createSession("main")
binding.terminal.attachSession(session)
session.run("bash") // Start interactive shell
```

### Multiple Sessions

```kotlin
val session1 = termux.sessions.createSession("python-repl")
val session2 = termux.sessions.createSession("node-server")

session1.run("python3 myapp.py")
session2.run("node server.js")

termux.sessions.closeSession(session1.session.id)
```

### Package Management

```kotlin
// Install packages
termux.packages.install("python", "nodejs", "git", "curl")
termux.packages.pipInstall("requests", "numpy", "flask")
termux.packages.npmInstall("express", "axios")

// Search
val results = termux.packages.search("machine learning")

// List installed
val installed = termux.packages.listInstalled()
```

### File Operations

```kotlin
// Write a Python script to HOME
termux.bridge.writeFile("app.py", """
    import flask
    app = flask.Flask(__name__)

    @app.route('/')
    def hello(): return "Hello from LibTermux!"

    app.run(host='127.0.0.1', port=8080)
""")

// Run it
termux.bridge.run("python3 ~/app.py")

// Read output
val content = termux.bridge.readFile("output.txt")
```

---

## 🐧 OS Module — Embedded Linux Distros

The OS module lets you embed a full Linux distribution (Kali, Ubuntu, Debian, Alpine, Fedora) directly inside your Android app — no separate app, no root required. Users get a real `apt`/`apk`/`dnf` package manager, real binaries, and optionally a full graphical desktop rendered inside a Jetpack Compose view.

---

### How It Works

```
Your App (Jetpack Compose)
    └── LibTermux
            └── OsEnvironment
                    ├── ProotRunner (no root)   ← userspace chroot via proot
                    │       └── Kali rootfs / Ubuntu rootfs / ...
                    └── ChrootRunner (root)     ← real kernel chroot via su
                            └── Kali rootfs / Ubuntu rootfs / ...
```

**Without root** — [proot](https://proot-me.github.io/) intercepts syscalls in userspace and fakes a chroot. No root permission needed. Performance overhead ~15%.

**With root** — real `chroot(2)` syscall. Native speed. Full `iptables`, raw sockets, and partial `systemd` support.

Root detection is automatic (`ExecutionMode.AUTO`). You can override with `ExecutionMode.PROOT` or `ExecutionMode.REAL_CHROOT`.

---

### Supported Distros

| Distro | ID | Rootfs Size | Package Manager | GUI Support |
|--------|----|-------------|-----------------|-------------|
| Kali Linux (rolling) | `kali` | ~400 MB | `apt` | ✅ XFCE4 |
| Ubuntu 24.04 LTS | `ubuntu-24.04` | ~80 MB | `apt` | ✅ XFCE4 |
| Ubuntu 22.04 LTS | `ubuntu-22.04` | ~75 MB | `apt` | ✅ XFCE4 |
| Debian 12 Bookworm | `debian-12` | ~120 MB | `apt` | ✅ XFCE4 |
| Alpine 3.19 | `alpine` | ~5 MB | `apk` | ✅ XFCE4 |
| Fedora 40 | `fedora-40` | ~200 MB | `dnf` | ✅ XFCE4 |
| Custom | any | — | any | configurable |

All rootfs URLs point to **official ARM64 images** — no third-party mirrors.

---

### Installation

#### `build.gradle.kts` (app module)

```kotlin
dependencies {
    implementation("com.aeoncorex:libtermux-core:<version>")
}
```

#### `libs.versions.toml`

```toml
[versions]
libtermux = "<version>"

[libraries]
libtermux-core = { module = "com.aeoncorex:libtermux-core", version.ref = "libtermux" }
```

#### `AndroidManifest.xml`

```xml
<!-- Required: download rootfs archives -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Optional: keep VNC session alive in background -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
```

---

### Quick Start

#### 1 — Declare supported distros

Only declared distros are available in your app. This prevents accidental installation of unwanted rootfs archives.

```kotlin
// Application.onCreate() or wherever you init LibTermux
val libtermux = LibTermux.init(applicationContext, termuxConfig {
    autoInstall = true

    os {
        // Mode: AUTO detects root automatically
        executionMode = ExecutionMode.AUTO

        // Declare which distros your app supports
        registry {
            distro(Distro.Kali) {
                guiEnabled         = true                        // show desktop in app
                desktopEnvironment = DesktopEnvironment.XFCE4   // desktop to install
                defaultResolution  = DisplayResolution.HD_720P  // 1280×720
                description        = "Kali penetration testing suite"
                extraPackages      = listOf("nmap", "metasploit-framework", "sqlmap")
                startupCommands    = listOf("service postgresql start")
            }

            distro(Distro.Ubuntu2404) {
                guiEnabled    = false                            // CLI only
                description   = "Ubuntu development environment"
                extraPackages = listOf("python3-pip", "nodejs", "git", "curl")
            }

            distro(Distro.Alpine) {
                guiEnabled = false
                description = "Minimal Alpine (fast download)"
            }
        }
    }
})
```

#### 2 — Install a distro

```kotlin
// In a ViewModel or Composable with LaunchedEffect
libtermux.os.setupDistro(Distro.Kali).collect { state ->
    when (state) {
        is DistroSetupState.Checking          -> showStatus("Checking...")
        is DistroSetupState.AlreadyInstalled  -> showStatus("Already ready")
        is DistroSetupState.InstallingProot   -> showStatus("Installing proot...")
        is DistroSetupState.Downloading       -> {
            showProgress(state.progress)
            showStatus("Downloading ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}")
        }
        is DistroSetupState.VerifyingChecksum -> showStatus("Verifying...")
        is DistroSetupState.Extracting        -> {
            showProgress(state.progress)
            showStatus("Extracting...")
        }
        is DistroSetupState.ConfiguringDistro -> showStatus("Configuring: ${state.step}")
        is DistroSetupState.Completed         -> showStatus("Done!")
        is DistroSetupState.Failed            -> showError(state.reason)
        else -> {}
    }
}
```

#### 3 — Run CLI commands

```kotlin
// Single command
val result = libtermux.os.execute(Distro.Kali, "nmap -sV 192.168.1.1")
println(result.stdout)
println("Exit: ${result.exitCode}")

// With custom working directory
val result2 = libtermux.os.execute(Distro.Ubuntu2404, "ls -la", workDir = "/etc")

// Install packages
libtermux.os.install(Distro.Kali, "hydra", "john")

// Update package lists
libtermux.os.update(Distro.Ubuntu2404)

// Run Python
libtermux.os.python(Distro.Ubuntu2404, """
    import platform
    print(f"Python {platform.python_version()} on {platform.system()}")
""")

// Check binary availability
val hasNmap = libtermux.os.hasBinary(Distro.Kali, "nmap")

// Interactive shell (attach to terminal view)
val process = libtermux.os.login(Distro.Kali)
```

---

### 🖥️ GUI Desktop (VNC)

When `guiEnabled = true`, the OS module starts a VNC server (TigerVNC + XFCE4) inside the distro and renders it in a Jetpack Compose `Canvas` with full mouse and keyboard support.

```
Distro container
    └── Xvnc :1 (port 5901) + XFCE4
            ↑ RFB 3.8 protocol (localhost only)
Android App
    └── VncClient (pure Kotlin RFB implementation)
            └── DistroDisplay (Compose Canvas)
                    ├── Touch → mouse events
                    ├── Long press → right click
                    ├── Two-finger drag → scroll wheel
                    ├── Pinch → zoom display
                    └── Hardware/soft keyboard → X11 keysyms
```

#### Show desktop in your Compose UI

```kotlin
@Composable
fun MyScreen(libtermux: LibTermux) {
    val coroutineScope = rememberCoroutineScope()
    var session by remember { mutableStateOf<DesktopSession?>(null) }

    // Create and start the session
    LaunchedEffect(Unit) {
        val s = libtermux.os.createDesktopSession(Distro.Kali)
        val settings = DistroRuntimeSettings(
            distroId      = Distro.Kali.id,
            displayWidth  = 1280,
            displayHeight = 720,
        )
        s.start(settings)
        session = s
    }

    session?.let { s ->
        // Full-screen desktop
        DistroDisplay(
            session     = s,
            modifier    = Modifier.fillMaxSize(),
            showToolbar = true,
            onClose     = { coroutineScope.launch { s.stop() } },
        )
    }
}
```

#### Desktop toolbar controls

The built-in toolbar (shown at top of `DistroDisplay`) provides:

| Button | Action |
|--------|--------|
| `C+A+D` | Send Ctrl+Alt+Delete |
| `⛶` | Toggle scale-to-fit / 1:1 pixel |
| `✕` | Stop session and close |

#### Touch controls

| Gesture | Mouse action |
|---------|-------------|
| Single tap | Left click |
| Long press | Right click |
| Drag | Mouse move + left button held |
| Two-finger drag up | Scroll wheel up |
| Two-finger drag down | Scroll wheel down |
| Pinch in/out | Zoom display (does not change desktop resolution) |

---

### 📋 Distro Launcher UI

Drop the built-in launcher into any Compose screen to give users a distro management UI:

```kotlin
@Composable
fun DistrosScreen(libtermux: LibTermux, navController: NavController) {
    DistroLauncher(
        os       = libtermux.os,
        modifier = Modifier.fillMaxSize(),
        onLaunch = { session ->
            // Navigate to desktop screen with the active session
            navController.navigate("desktop")
        },
        onOpenSettings = { distro ->
            navController.navigate("distro-settings/${distro.id}")
        },
    )
}
```

The launcher automatically shows only the distros registered in your `DistroRegistry`. Each card displays:
- Install status badge (Not installed / Installing / Ready)
- GUI and resolution capability chips
- Real-time install progress with download percentage
- Install / Launch Desktop / Settings / Uninstall actions

---

### ⚙️ Settings Screen

```kotlin
@Composable
fun DistroSettingsRoute(distroId: String, libtermux: LibTermux) {
    val distro          = Distro.fromId(distroId) ?: return
    val supportedDistro = libtermux.os.registry[distro] ?: return

    DistroSettingsScreen(
        distro          = distro,
        supportedDistro = supportedDistro,
        onBack          = { /* navigate back */ },
    )
}
```

Settings are persisted via **DataStore Preferences** and survive app restarts.

| Setting | Description |
|---------|-------------|
| Resolution | 720p / 1080p / 1200p / Custom |
| Color depth | 16 / 24 / 32 bit |
| Scale to fit | Stretch desktop to fill screen |
| VNC Port | Default 5901 (display :1) |
| VNC Password | Empty = no authentication |
| Startup commands | Run on every session start |
| Show toolbar | Toggle the overlay toolbar |
| Vibrate on click | Haptic feedback on mouse click |

---

### Custom Distro

```kotlin
distro(Distro.Custom(
    customId          = "my-distro",
    customName        = "My Distro",
    url               = "https://example.com/my-rootfs-arm64.tar.gz",
    compressionType   = CompressionType.GZ,
    shell             = "/bin/bash",
)) {
    guiEnabled         = true
    desktopEnvironment = DesktopEnvironment.OPENBOX
    defaultResolution  = DisplayResolution.FHD_1080P
}
```

---

### Root vs No-Root

```kotlin
// Check mode at runtime
val isRooted     = libtermux.isRooted
val mode         = libtermux.os.executionMode        // PROOT or REAL_CHROOT
val usingChroot  = libtermux.os.isUsingRealChroot

// Force a specific mode
termuxConfig {
    os {
        executionMode = ExecutionMode.PROOT        // always use proot
        // OR
        executionMode = ExecutionMode.REAL_CHROOT  // require root
    }
}
```

| Feature | No Root (proot) | Root (chroot) |
|---------|----------------|---------------|
| Setup | ✅ No root needed | ✅ Needs `su` |
| Performance | ✅ ~85% native | ✅ 100% native |
| `apt install` | ✅ | ✅ |
| Metasploit | ✅ | ✅ |
| Raw sockets | ❌ | ✅ |
| `iptables` | ❌ | ✅ |
| WiFi injection | ❌ | ❌ (kernel driver needed) |
| Systemd services | ❌ | ⚠️ partial |
| Kernel modules | ❌ | ❌ |
| GUI (VNC) | ✅ | ✅ |

---

### Known Limitations

- **WiFi injection / monitor mode** — requires a custom Android kernel (e.g. NetHunter). Not possible via library alone.
- **systemd** — Android kernel lacks full cgroup v2 support required by systemd. Use `service` or `openrc` instead.
- **Kernel modules** — `modprobe`/`insmod` are not possible without matching kernel source.
- **GPU acceleration** — Vulkan/OpenGL inside the container is not supported. CPU-only.
- **Rootfs size** — Kali minimal is ~400 MB compressed, ~1.5 GB extracted. Ensure sufficient internal storage or set `distroStorageDir` to external storage.
- **VNC encoding** — Current implementation supports Raw and CopyRect. ZRLE/ZLIB coming in a future release for better compression.

---

### Architecture Reference

```
com.libtermux.os/
├── OsConfig.kt                ← configuration + ExecutionMode + OsConfigDsl
├── OsEnvironment.kt           ← main public API
├── RootUtils.kt               ← root detection + su execution
├── distro/
│   ├── Distro.kt              ← sealed class with official ARM64 URLs
│   └── DistroSetupState.kt    ← Flow progress states
├── registry/
│   ├── SupportedDistro.kt     ← per-distro developer config + DSL builder
│   └── DistroRegistry.kt      ← registry of declared distros
├── settings/
│   └── DistroSettings.kt      ← DataStore-backed runtime settings
├── proot/
│   └── ProotRunner.kt         ← proot engine (no root)
├── chroot/
│   ├── ChrootRunner.kt        ← real chroot engine (root)
│   └── MountManager.kt        ← /proc /sys /dev bind mounts
└── gui/
    ├── DesktopSession.kt       ← VNC server lifecycle manager
    ├── vnc/
    │   ├── VncClient.kt        ← pure Kotlin RFB 3.8 protocol client
    │   └── VncState.kt         ← connection states + KeySym + MouseButton
    └── compose/
        ├── DistroDisplay.kt         ← Canvas desktop rendering + input
        ├── DistroLauncher.kt        ← distro picker + install UI
        └── DistroSettingsScreen.kt  ← per-distro settings UI
```
---

## 🔐 Shizuku Integration (Elevated Privileges)

libtermux-android optionally integrates with Shizuku to run commands with elevated (root/system) privileges.
This allows your app to perform system-level operations like accessing protected files, modifying system settings, or executing privileged commands—all without requiring the user to manually install Shizuku or root their device (Shizuku can run via ADB or root).

The library provides a simple API that falls back to normal execution when Shizuku is unavailable, so your app remains functional in all environments.

## Installation

Add the Shizuku module dependency:

```kotlin
dependencies {
    implementation("com.github.libtermux:libtermux-android:1.0.0")
    implementation("com.github.libtermux:shizuku:1.0.0") // optional: elevated commands
}
```

## Usage

```kotlin
class MyActivity : AppCompatActivity() {
    private lateinit var shizukuTermux: ShizukuTermux

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        shizukuTermux = ShizukuTermux.getInstance(this) {
            autoInstall = true
        }

        lifecycleScope.launch {
            shizukuTermux.initialize().collect { state ->
                if (state is InstallState.Completed) {
                    // Request Shizuku permission if needed
                    if (!shizukuTermux.isShizukuPermissionGranted) {
                        shizukuTermux.requestShizukuPermission()
                    }

                    // Run normal command
                    val normal = shizukuTermux.bridge.run("echo Hello")
                    println(normal.stdout)

                    // Run elevated command (e.g., read system files)
                    val elevated = shizukuTermux.runElevated("cat /system/build.prop")
                    println("Exit code: ${elevated.exitCode}")
                }
            }
        }
    }
}
```

## Permission

Add the following permission to your AndroidManifest.xml:

```xml
<uses-permission android:name="dev.rikka.shizuku.permission.API_V23" />
```

Optionally, include the Shizuku provider for automatic service binding:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:enabled="true"
    android:exported="false" />
```

## API Reference

**Method Description**

ShizukuTermux.getInstance(context, config) Get singleton instance (also supports DSL).
isShizukuAvailable Check if Shizuku service is installed and running.
isShizukuPermissionGranted Check if permission has been granted.
suspend fun requestShizukuPermission() Request permission (suspends until granted/denied).
suspend fun runElevated(command, env, workDir) Execute command with elevated privileges. Returns ElevatedResult.

## Fallback Behavior

If Shizuku is not installed, not running, or permission is denied, runElevated() automatically falls back to normal execution within the Termux environment. You can check the elevated flag in the result to know how it was executed.

---

## 🏗️ Project Structure

```
libtermux-android/
├── core/                          # Main SDK library
│   └── src/main/
│       ├── kotlin/com/libtermux/
│       │   ├── LibTermux.kt       # ← Main entry point
│       │   ├── TermuxConfig.kt    # ← Configuration DSL
│       │   ├── bootstrap/         # Bootstrap installer
│       │   ├── executor/          # Command execution engine
│       │   ├── fs/                # Virtual file system
│       │   ├── pkg/               # Package manager
│       │   ├── bridge/            # Developer API bridge
│       │   ├── service/           # Background service
│       │   └── utils/             # Utilities + JNI bindings
│       └── jni/                   # C++ native layer (PTY, process, symlinks)
│
├── terminal-view/                 # Terminal UI widget (optional)
│   └── src/main/kotlin/
│       └── com/libtermux/view/
│           └── TerminalView.kt
│
├── shizuku/                       # Shizuku integration for elevated commands (optional)
│   └── src/main/kotlin/
│       └── com/libtermux/shizuku/
│           ├── ShizukuTermux.kt   # Entry point for elevated execution
│           └── ElevatedResult.kt  # Result class for elevated commands
│
├── sample/                        # Demo app
│   └── src/main/kotlin/
│       └── com/libtermux/sample/
│           ├── MainActivity.kt
│           └── MainViewModel.kt
│
├── scripts/
│   ├── setup_dev.sh               # Developer environment setup
│   └── publish_local.sh           # Publish to local Maven
│
└── .github/workflows/
    ├── ci.yml                     # CI: lint + test + build
    └── release.yml                # Release: publish to Maven Central
```

---

## 🔐 Permissions

Add to your `AndroidManifest.xml`:

```xml
<!-- Required for bootstrap download -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Required for background service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />

<!-- Optional: for notifications -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

---

## 📊 Architecture

```
┌──────────────────────────────────────────────────────────┐
│                      Your Android App                     │
│                                                           │
│  LibTermux.create(context) { ... }                        │
│       │                                                   │
│  ┌────▼────────────────────────────────────────────────┐  │
│  │              LibTermux (Singleton)                  │  │
│  │  ┌─────────────┐  ┌──────────────┐  ┌───────────┐  │  │
│  │  │TermuxBridge │  │SessionManager│  │PackageMgr │  │  │
│  │  └──────┬──────┘  └──────┬───────┘  └─────┬─────┘  │  │
│  │         └───────────────┬┘                │         │  │
│  │                  ┌──────▼──────┐           │         │  │
│  │                  │CommandExec  │◄──────────┘         │  │
│  │                  └──────┬──────┘                     │  │
│  │  ┌────────────────────┐ │ ┌──────────────────────┐   │  │
│  │  │VirtualFileSystem   │ │ │BootstrapInstaller    │   │  │
│  │  │  /usr /home /tmp   │◄┘ │  (Download + Extract)│   │  │
│  │  └────────────────────┘   └──────────────────────┘   │  │
│  │                                                       │  │
│  │  ┌────────────────────────────────────────────────┐   │  │
│  │  │     libtermux_jni.so  (C++ Native Layer)        │  │  │
│  │  │  PTY · Process Fork · Symlinks · chmod          │  │  │
│  │  └────────────────────────────────────────────────┘   │  │
│  └────────────────────────────────────────────────────┘  │
│                                                           │
│  ┌──────────────────────────────────────────────────────┐ │
│  │   App Private Storage: filesDir/libtermux/            │ │
│  │   └── usr/bin/bash, python3, node ...                 │ │
│  └──────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────┘
```

---

## 🤝 Contributing

1. Fork the repo
2. Run `./scripts/setup_dev.sh`
3. Create a feature branch: `git checkout -b feature/amazing-feature`
4. Run tests: `./gradlew :core:testDebugUnitTest`
5. Push and open a Pull Request

---

## 📄 License

```
Copyright 2026 LibTermux Contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0
```
