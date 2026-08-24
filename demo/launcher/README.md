# Minecraft Launcher Demo

This is a simple terminal-based launcher for Minecraft: Java Edition. It can manage accounts, install official Minecraft
versions, and launch the game.

The launcher is a repository demo and is not published as a downloadable application. Each install task creates a
runnable distribution below `demo/launcher/build/install/launcher-<target>`.

> [!IMPORTANT]
> Gradle cannot correctly forward keyboard input to the TUI, so this demo cannot be run as a Gradle task. Install the
> distribution first, then run the packaged launcher directly.

## Requirements

The computer running the launcher must have `java` available on `PATH`. Run each build command from the repository root.
Run a native install task on a host supported by its Kotlin/Native target.
The launcher creates its state, downloaded Minecraft files, and game files in the artifact directory from which it is
started.

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
