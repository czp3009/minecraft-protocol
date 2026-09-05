# Minecraft Launcher Demo

This repository includes a terminal launcher for Minecraft: Java Edition. It can browse Mojang's version manifest,
install compatible official game files, manage offline or Microsoft accounts, and launch the official Java client while
showing its output in the terminal.

The launcher is an integration demo, not a supported or published end-user application. Each install task creates a
runnable distribution below `demo/launcher/build/install/launcher-<target>`.

> [!IMPORTANT]
> Gradle cannot correctly forward keyboard input to the TUI, so this demo cannot be run as a Gradle task. Install the
> distribution first, then run the packaged launcher directly.

## Requirements

The computer running the launcher must have `java` available on `PATH`. Run each build command from the repository root.
The command must provide at least the Java major required by the selected game version. Run a native install task only
on a host supported by that Kotlin/Native target.

The launcher's working directory is its state directory. It creates `auth.json`, `installed.json`, and a `minecraft/`
directory there, so start it from a dedicated writable directory. Microsoft refresh and Minecraft access tokens are
stored unencrypted in `auth.json`; use the demo only in a trusted local environment and do not share that file.

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

## Interface

The home screen provides access to version installation, installed versions, and account management:

![Launcher home screen](docs/images/img.png)

While Minecraft is running, the launcher displays its process output in the TUI:

![Minecraft process output](docs/images/img_1.png)

## JVM

Install the JVM application distribution:

```shell
./gradlew :demo:launcher:installJvmDist
```

Enter the generated application directory and run it on Windows:

```powershell
Set-Location demo/launcher/build/install/launcher-jvm
.\bin\launcher.bat
```

On Linux, use the generated shell script instead:

```shell
cd demo/launcher/build/install/launcher-jvm
./bin/launcher
```

## Windows Native

Install the x64 executable distribution:

```powershell
.\gradlew.bat :demo:launcher:installMingwX64Executable
```

Enter the generated application directory and run it:

```powershell
Set-Location demo/launcher/build/install/launcher-mingwX64
.\launcher.exe
```

## Linux Native

Install the x64 executable distribution:

```shell
./gradlew :demo:launcher:installLinuxX64Executable
```

Enter the generated application directory and run it:

```shell
cd demo/launcher/build/install/launcher-linuxX64
./launcher.kexe
```

On Linux ARM64, install the matching executable distribution:

```shell
./gradlew :demo:launcher:installLinuxArm64Executable
```

Enter the generated application directory and run it:

```shell
cd demo/launcher/build/install/launcher-linuxArm64
./launcher.kexe
```

## macOS Native

Install the ARM64 executable distribution:

```shell
./gradlew :demo:launcher:installMacosArm64Executable
```

Enter the generated application directory and run it:

```shell
cd demo/launcher/build/install/launcher-macosArm64
./launcher.kexe
```
