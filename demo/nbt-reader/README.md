# NBT reader demo

A small, read-only CLI demonstrating [nbt](../../nbt/README.md) and
[nbt-serialization](../../nbt-serialization/README.md). It reads one standalone Java Edition binary NBT file,
automatically detects raw or GZIP input, and prints a typed tree. The binary root name is retained, including an empty
name. It can also print the complete root value as SNBT.

Argument parsing uses [kotlinx-cli](https://github.com/Kotlin/kotlinx-cli).

All targets run through Gradle. No installation or packaging step is needed. Run the commands below from the repository
root with the repository's required Java toolchain available. Replace `world/level.dat` with an existing NBT file;
relative input paths resolve from the repository root.

## Run

The shared `nbtReaderArgs` Gradle property contains the CLI arguments. Omitting it prints help. Use single quotes
inside the property value for a path containing spaces, for example `"-PnbtReaderArgs='my world/level.dat' --depth 2"`.
The argument text is parsed into arguments without invoking a shell.

On Windows, run the JVM or Windows x64 Native application from PowerShell:

```powershell
.\gradlew.bat -q :demo:nbt-reader:jvmRun "-PnbtReaderArgs=world/level.dat"
.\gradlew.bat -q :demo:nbt-reader:runDebugExecutableMingwX64 "-PnbtReaderArgs=world/level.dat"
```

On Linux or macOS, the JVM command is:

```shell
./gradlew -q :demo:nbt-reader:jvmRun "-PnbtReaderArgs=world/level.dat"
```

Choose the Native task matching your operating system and architecture:

| Host                | Task                                            |
|---------------------|-------------------------------------------------|
| Windows x64         | `:demo:nbt-reader:runDebugExecutableMingwX64`   |
| Linux x64           | `:demo:nbt-reader:runDebugExecutableLinuxX64`   |
| macOS Apple silicon | `:demo:nbt-reader:runDebugExecutableMacosArm64` |

For example, on Apple silicon:

```shell
./gradlew -q :demo:nbt-reader:runDebugExecutableMacosArm64 "-PnbtReaderArgs=world/level.dat"
```

Native executables must be built and run on their matching host. JVM execution is available on all three desktop
operating systems. The JVM task also accepts Gradle's standard `--args` option instead of `nbtReaderArgs`.

## Output

An illustrative tree looks like this:

```text
File: "player.dat"
Compression: gzip
Root name: ""
(root): Compound (3 entries)
  "Health": Float = 18.0
  "foodLevel": Int = 20
  "Pos": List (3 elements)
    [0]: Double = 128.5
    [1]: Double = 64.0
    [2]: Double = -32.5
```

Each value retains its NBT type. Compounds retain input order, lists show indices and individual element types, and
primitive arrays show their length and a preview. Strings and names use the library's SNBT string escaping.

| Option                           | Default | Meaning                                                               |
|----------------------------------|---------|-----------------------------------------------------------------------|
| `--format tree\|snbt`            | `tree`  | Typed tree or complete SNBT root value                                |
| `--compression auto\|none\|gzip` | `auto`  | Detect GZIP by its header, or select the input compression explicitly |
| `--depth N`                      | `3`     | Tree expansion depth, with the root at depth zero                     |
| `--limit N`                      | `16`    | Maximum displayed children or array elements per container            |
| `--full`                         | off     | Disable tree depth, item, and string preview limits                   |
| `--help`, `-h`                   |         | Show CLI help                                                         |

Tree strings and names use a 160-character preview. Every omitted part is marked. Limits affect only display: the
complete NBT tree is decoded into memory. `--full` may produce substantial output.

For a shorter preview or SNBT output, using PowerShell:

```powershell
.\gradlew.bat -q :demo:nbt-reader:jvmRun "-PnbtReaderArgs=world/level.dat --depth 2 --limit 8"
.\gradlew.bat -q :demo:nbt-reader:jvmRun "-PnbtReaderArgs=world/level.dat --format snbt"
```

SNBT output has no file header or preview truncation and omits the binary root name. It uses `SnbtFormat`'s normal key
ordering and representability rules; values such as non-finite floating-point numbers and empty compound keys cause
an export error. File, decoding, and option-value validation failures report an English diagnostic and a nonzero exit.
Application errors go to stderr; argument syntax errors and help use kotlinx-cli's own output behavior.

This demo reads binary NBT files, including non-compound named roots. It does not read SNBT input, network root framing,
Bedrock NBT, or Anvil `.mca` containers, and does not edit files or interpret game-specific fields.

## Verification

```powershell
.\gradlew.bat :demo:nbt-reader:jvmTest
.\gradlew.bat :demo:nbt-reader:mingwX64Test
```

On Linux or macOS, use `./gradlew` and the matching `linuxX64Test` or `macosArm64Test` task. Portable
tests cover raw/GZIP decoding, root names, corrupt input, and tree previews using an in-memory filesystem.
