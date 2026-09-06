# Minecraft Launcher Demo

This repository includes a terminal launcher for Minecraft: Java Edition. It can browse Mojang's version manifest,
install compatible official game files, manage offline or Microsoft accounts, and launch the official Java client while
showing its output in the terminal.

The launcher is an integration demo, not a supported or published end-user application.

## Preview

The home screen provides access to version installation, installed versions, and account management:

![Launcher home screen](docs/images/img.png)

While Minecraft is running, the launcher displays its process output in the TUI:

![Minecraft process output](docs/images/img_1.png)

## Before running

The computer running the launcher must have `java` available on `PATH`, providing at least the Java major required by
the selected game version. This also applies to native launcher distributions, which still start the Java game client.

Choose one command block below for your platform and start it from the repository root. Each block installs a
distribution, enters its directory, and starts the launcher. Build a native distribution only on a host supported by
that Kotlin/Native target.

> [!IMPORTANT]
> Gradle cannot correctly forward keyboard input to the TUI. Install the distribution first, then run the packaged
> launcher directly as shown below.

The launcher's working directory is its state directory. It creates `auth.json`, `installed.json`, and a `minecraft/`
directory there. The examples use the generated distribution directory below `demo/launcher/build/install/`; you can
instead start the packaged launcher from another dedicated writable directory. Microsoft refresh and Minecraft access
tokens are stored unencrypted in `auth.json`; use the demo only in a trusted local environment and do not share that
file.

## Windows

In PowerShell, install and start the JVM launcher:

```powershell
.\gradlew.bat :demo:launcher:installJvmDist
Set-Location demo/launcher/build/install/launcher-jvm
.\bin\launcher.bat
```

Or install and start the native x64 launcher:

```powershell
.\gradlew.bat :demo:launcher:installMingwX64Executable
Set-Location demo/launcher/build/install/launcher-mingwX64
.\launcher.exe
```

## Linux

Install and start the JVM launcher:

```shell
./gradlew :demo:launcher:installJvmDist
cd demo/launcher/build/install/launcher-jvm
./bin/launcher
```

Or install and start the native launcher matching your machine architecture.

For x64:

```shell
./gradlew :demo:launcher:installLinuxX64Executable
cd demo/launcher/build/install/launcher-linuxX64
./launcher.kexe
```

For ARM64:

```shell
./gradlew :demo:launcher:installLinuxArm64Executable
cd demo/launcher/build/install/launcher-linuxArm64
./launcher.kexe
```

## macOS

In Terminal, install and start the JVM launcher:

```shell
./gradlew :demo:launcher:installJvmDist
cd demo/launcher/build/install/launcher-jvm
./bin/launcher
```

Or, on Apple silicon, install and start the native launcher:

```shell
./gradlew :demo:launcher:installMacosArm64Executable
cd demo/launcher/build/install/launcher-macosArm64
./launcher.kexe
```

## Supported versions

The version list, installation metadata, and download streams come from
[distribution-metadata](../../distribution-metadata/README.md).
The launcher supports that module's modern schema; historical entries remain visible in the manifest but fail to decode
if they lack required modern fields. Installation rejects unsafe paths and unknown launch rules.

Launches use the metadata's default user JVM options followed by its version JVM arguments, preserving rule order and
individual argument boundaries. OS version ranges use the detected host version, including the Windows build number;
rules for other operating systems or architectures are skipped. The demo has no custom JVM-options setting.

Downloaded client, library, logging, and asset files are checked against their declared size and SHA-1 before an
installation is recorded. Metadata documents are decoded through the shared HTTP client, and the asset index is saved
as JSON without metadata hash or size checks.
