# Minecraft Launcher Demo

This is a simple terminal-based launcher for Minecraft: Java Edition. It can manage accounts, install official Minecraft
versions, and launch the game.

The launcher is a repository demo and is not published as a downloadable application. Build it with Gradle, then run the
generated artifact directly in a terminal.

## Requirements

The computer running the launcher must have `java` available on `PATH`. Run each build command from the repository root.
The launcher creates its state, downloaded Minecraft files, and game files in the artifact directory from which it is
started.

## JVM

Build the JVM application directory:

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

Build an x64 executable and install it together with the three MinGW runtime DLLs required by Kommand:

```powershell
.\gradlew.bat :demo:launcher:installMingwX64Executable
```

```powershell
Set-Location demo/launcher/build/install/launcher-mingwX64
.\launcher.exe
```

## Linux Native

Build and run the x64 executable:

```shell
./gradlew :demo:launcher:linkReleaseExecutableLinuxX64
cd demo/launcher/build/bin/linuxX64/releaseExecutable
./launcher.kexe
```

On Linux ARM64, use `linkReleaseExecutableLinuxArm64` and the
`demo/launcher/build/bin/linuxArm64/releaseExecutable` directory instead.
