# Okio 3.18.1 MinGW `FileHandle.resize(0)` 误报失败

## 摘要

Okio 3.18.1 的 Kotlin/Native `mingwX64` 实现中，`FileSystem.SYSTEM.openReadWrite(...).resize(0L)` 会把一次成功的 Win32
文件指针移动误判为失败，并抛出：

```text
okio.IOException: The operation completed successfully.
```

这个消息不是操作系统报告了一种矛盾的 I/O 状态，而是 Okio 对 [
`SetFilePointer` 返回值](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
的判断有误：目标位置为 `0` 时，`SetFilePointer` 成功返回 `0`；Okio 却把任何返回值 `0` 都当成失败，然后将仍为 [
`ERROR_SUCCESS`](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-#error_success) 的
last-error 格式化成异常消息。

该问题可在不进行并发 I/O、不使用大文件、也不依赖概率性时序的情况下稳定复现。

## 问题性质：Win32 API 的特殊性诱发了 Okio 缺陷

这个问题包含两个层次，需要区分：

1. **Win32 `SetFilePointer` 的返回值设计确实比较特殊。**根据 Microsoft 的 [
   `SetFilePointer` 返回值说明](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
   ，它不是使用常见的 `BOOL` 表示成功或失败，而是直接返回新文件指针的低 32 位。成功移动到偏移 `0` 时自然返回 `0`，所以 `0`
   不是失败哨兵。真正的失败候选值是 `INVALID_SET_FILE_POINTER`（`0xffffffff`）；但该数值又可能恰好是某个成功位置的低 32
   位，因此在这种情况下还必须结合 `GetLastError()` 是否为 `NO_ERROR` 判断。
2. **最终抛出异常是 Okio 的实现错误。**[
   `SetFilePointer` 文档给出的失败判断](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
   是 `result == INVALID_SET_FILE_POINTER && GetLastError() != NO_ERROR`，Okio 3.18.1 的 MinGW 实现却写成 `result == 0U`
   。因此不能把问题归因于“Windows 偶尔会抛出成功消息”或文件系统随机异常；Windows 返回了符合契约的成功结果，Okio 将它解释错了。

Microsoft 的 [
`GetLastError` 文档](https://learn.microsoft.com/en-us/windows/win32/api/errhandlingapi/nf-errhandlingapi-getlasterror#return-value)
说明，并非每个 API 成功后都会清零 last-error；如果目标函数没有声明成功时设置错误码，读到的可能只是线程最近一次保存的值。调用方应当只在目标
API 的返回值表明错误信息有效时读取它。这里 Okio 在一个成功的 `SetFilePointer` 调用后错误进入失败分支；当线程的 last-error
恰好是 [`ERROR_SUCCESS`（数值
`0`）](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-#error_success) 时，Okio 将它作为 [
`FormatMessageW` 的
`dwMessageId`](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-formatmessagew#parameters)
从系统消息表格式化，于是生成看似矛盾的文本：

```text
The operation completed successfully.
```

所以这条消息的准确含义是： **异常构造过程读取并格式化了 Win32 定义为成功的[错误码
`0`](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-#error_success)**。它不表示 Win32
主动抛出了一个“成功异常”，也不能据此推断存在并发或 overlapping I/O。

## 已确认的环境

- Windows x64
- Kotlin Multiplatform 2.4.10
- Kotlin/Native target：`mingwX64`
- Okio 3.18.1
- Gradle 9.6.1

## 最小复现工程

目录结构：

```text
okio-mingw-file-handle-repro/
├── settings.gradle.kts
├── build.gradle.kts
└── src/
    └── mingwMain/
        └── kotlin/
            └── Main.kt
```

### `settings.gradle.kts`

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "okio-mingw-file-handle-repro"
```

### `build.gradle.kts`

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.10"
}

kotlin {
    mingwX64 {
        binaries {
            executable {
                entryPoint = "main"
            }
        }
    }

    sourceSets {
        mingwMain.dependencies {
            implementation("com.squareup.okio:okio:3.18.1")
        }
    }
}
```

### `src/mingwMain/kotlin/Main.kt`

```kotlin
import okio.FileSystem
import okio.buffer
import okio.use

fun main() {
    val fileSystem = FileSystem.SYSTEM
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "okio-mingw-resize-repro.dat"

    try {
        fileSystem.sink(path).buffer().use { sink ->
            sink.writeUtf8("seed")
        }

        fileSystem.openReadWrite(path, mustExist = true).use { handle ->
            handle.resize(0L)
        }

        error("Expected Okio 3.18.1 resize(0) to fail on mingwX64")
    } finally {
        fileSystem.delete(path, mustExist = false)
    }
}
```

### 运行

如果工程还没有 Gradle wrapper，可先使用本机 Gradle 生成：

```powershell
gradle wrapper --gradle-version 9.6.1
```

然后运行 MinGW 可执行程序：

```powershell
.\gradlew.bat runDebugExecutableMingwX64 --stacktrace --console=plain
```

也可以在构建后直接运行：

```powershell
.\build\bin\mingwX64\debugExecutable\okio-mingw-file-handle-repro.exe
```

## 实际结果

程序稳定以未捕获异常退出。关键栈如下：

```text
Uncaught Kotlin exception: okio.IOException: The operation completed successfully.
    at okio.lastErrorToIOException
    at okio.WindowsFileHandle.protectedResize
    at okio.FileHandle.resize
    at MainKt.main
```

## 预期结果

`resize(0L)` 正常返回，随后：

```kotlin
check(fileSystem.metadata(path).size == 0L)
```

应当成立。

## 根因

Okio 3.18.1 的 MinGW `WindowsFileHandle.protectedResize` 使用 `SetFilePointer` 定位新的 EOF，然后调用 `SetEndOfFile`
。其失败判断等价于：

```kotlin
val result = SetFilePointer(
    hFile = file,
    lDistanceToMove = size.toInt(),
    lpDistanceToMoveHigh = distanceToMoveHigh.ptr,
    dwMoveMethod = FILE_BEGIN.toUInt(),
)
if (result == 0U) {
    throw lastErrorToIOException()
}
```

这个判断不符合 Microsoft [
`SetFilePointer` 返回值契约](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)。

[
`SetFilePointer` 成功时返回新文件指针的低 32 位](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
。因此把文件指针移动到偏移 `0` 时，成功返回值就是 `0`。失败返回值是 `INVALID_SET_FILE_POINTER`（`0xffffffff`），而不是 `0`。

此外，[
`INVALID_SET_FILE_POINTER` 本身也可能是合法位置的低 32 位](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
。按照该文档，调用方需要检查：

1. 返回值是否为 `INVALID_SET_FILE_POINTER`；
2. `GetLastError()` 是否不是 `NO_ERROR`。

在本复现中发生的实际流程是：

```text
resize(0)
  -> SetFilePointer(..., distance = 0)
  -> 成功，返回新的低 32 位位置 0
  -> Okio 将返回值 0 误判为失败
  -> Okio 调用 GetLastError()
  -> last-error 为 ERROR_SUCCESS（数值 0）
  -> FormatMessageW(0) 得到 "The operation completed successfully."
  -> Okio 将该文本包装为 IOException
```

因此，结合 [`SetFilePointer` 对返回值
`0` 的定义](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)、[
`ERROR_SUCCESS` 的系统错误码定义](https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499-#error_success)
，以及 [`FormatMessageW` 对
`dwMessageId` 的处理](https://learn.microsoft.com/en-us/windows/win32/api/winbase/nf-winbase-formatmessagew#parameters)
，这条异常消息恰好反映了本次 Win32 操作已经成功，而不是文件系统发生了重叠读取、共享冲突或异步 I/O 失败。

这个错误也会影响目标位置低 32 位恰好为零的其他文件大小；最直接且无需大文件的稳定复现就是 `resize(0L)`。

## JVM 实现为何不会触发该问题

Okio 3.18.1 的 JVM `JvmFileHandle` 不直接调用 `SetFilePointer`。它持有 `java.io.RandomAccessFile`，并按扩容和缩容分别处理
`resize`：

```kotlin
@Synchronized
override fun protectedResize(size: Long) {
    val currentSize = size()
    val delta = size - currentSize
    if (delta > 0) {
        protectedWrite(currentSize, ByteArray(delta.toInt()), 0, delta.toInt())
    } else {
        randomAccessFile.setLength(size)
    }
}
```

对于本复现中的 `resize(0L)`：

```text
当前文件长度为 4
  -> delta = 0 - 4 = -4
  -> 进入缩容分支
  -> 调用 RandomAccessFile.setLength(0)
  -> 正常完成
```

JVM 路径不会看到 `SetFilePointer` 的低 32 位返回值，也就不会把合法的 `0` 当成失败。Windows 上的 JDK
会在其内部完成相应的本地调用和错误映射；Okio JVM 代码只接收 `RandomAccessFile.setLength` 成功返回或抛出的 Java I/O 异常。

JVM 实现还有两个与 `FileHandle` 契约相关的细节：

- **扩容显式补零。**当目标大小大于当前大小时，它从旧 EOF 开始写入零字节，而不是只移动 EOF。原因是 Microsoft 的 [
  `SetEndOfFile` 文档](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setendoffile#remarks)
  明确说明，直接扩展文件后新旧 EOF 之间的内容未定义；而 Okio `FileHandle.resize` 承诺扩展区域由 `0` 字节组成。
- **操作使用 `@Synchronized`。**`size`、`read`、`write`、`flush`、`resize` 和 `close` 都在同一个 `JvmFileHandle` 实例上串行执行，符合
  `FileHandle` 可被多个线程并发使用的公开契约。

因此，JVM 与 MinGW 的差异不是 JVM 对 `SetFilePointer` 的返回值做了另一种 Kotlin 判断，而是 JVM Okio 根本没有直接暴露这个容易误用的
Win32 API：它通过 `RandomAccessFile` 完成文件长度操作，并另外实现了补零与同步语义。

## 建议修复

最直接的选择是改用[返回 `BOOL` 的
`SetFilePointerEx`](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointerex#return-value)：

```text
SetFilePointerEx(handle, distance, null, FILE_BEGIN)
SetEndOfFile(handle)
```

也可以直接使用 [
`SetFileInformationByHandle`](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfileinformationbyhandle)：

```text
SetFileInformationByHandle(FileEndOfFileInfo)
```

如果继续使用 `SetFilePointer`
，则必须按照其[返回值文档](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer#return-value)
检查 `INVALID_SET_FILE_POINTER` 与 `GetLastError()`，不能把返回值 `0` 当作失败。

## 建议回归测试

在 Okio 的 `mingwX64` 系统文件句柄测试中加入确定性用例：

```kotlin
fileSystem.openReadWrite(path, mustExist = true).use { handle ->
    handle.resize(0L)
    check(handle.size() == 0L)
}
```

测试文件在调用前应包含至少一个字节，以确认该操作实际执行了截断。

## 参考资料

- Okio 3.18.1 MinGW sources
  JAR：<https://repo1.maven.org/maven2/com/squareup/okio/okio-mingwx64/3.18.1/okio-mingwx64-3.18.1-sources.jar>
- Okio 3.18.1 JVM sources
  JAR：<https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.18.1/okio-jvm-3.18.1-sources.jar>
- Microsoft `SetFilePointer`
  文档：<https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setfilepointer>
- Microsoft `SetEndOfFile` 文档：<https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-setendoffile>
- Microsoft system error code
  `ERROR_SUCCESS`：<https://learn.microsoft.com/en-us/windows/win32/debug/system-error-codes--0-499->
