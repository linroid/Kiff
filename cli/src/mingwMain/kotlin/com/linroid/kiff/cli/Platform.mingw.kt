package com.linroid.kiff.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import okio.BufferedSink
import okio.IOException
import okio.Path
import platform.posix._O_BINARY
import platform.posix._fileno
import platform.posix._setmode
import platform.posix.stdout
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetEnvironmentVariableW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.PROCESS_INFORMATION
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_INPUT_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetEnvironmentVariableW
import platform.windows.TRUE
import platform.windows.WCHARVar
import platform.windows.WaitForSingleObject

// Text mode would turn each \n of a diff into \r\n on its way out.
@OptIn(ExperimentalForeignApi::class)
internal actual fun standardOutput(): BufferedSink =
  standardOutputSink { _setmode(_fileno(stdout), _O_BINARY) }

@OptIn(ExperimentalForeignApi::class)
internal actual fun environment(name: String): String? = memScoped {
  // Read wide, since the narrow environment is in the ANSI code page, which a path need not fit.
  val size = GetEnvironmentVariableW(name, null, 0u)
  if (size == 0u) return@memScoped null
  val buffer = allocArray<WCHARVar>(size.toInt())
  GetEnvironmentVariableW(name, buffer, size)
  buffer.toKString()
}

internal actual fun homeDirectory(): String =
  environment("USERPROFILE")?.takeIf { it.isNotEmpty() }
    ?: throw IOException("USERPROFILE is not set")

@OptIn(ExperimentalForeignApi::class)
internal actual fun runCommand(command: List<String>, stdout: Path?, stderr: Path?): Int =
  memScoped {
    val output = stdout?.let { createInheritable(it) }
    val error = stderr?.let { createInheritable(it) }
    try {
      start(windowsCommandLine(command), command.first(), output, error)
    } finally {
      output?.let { CloseHandle(it) }
      error?.let { CloseHandle(it) }
    }
  }

@OptIn(ExperimentalForeignApi::class)
internal actual fun handOver(command: List<String>, environment: Map<String, String>): Int {
  for ((name, value) in environment) SetEnvironmentVariableW(name, value)
  val program = command.first()
  // A JVM release's launcher is a batch file, which only cmd.exe runs. With /s it takes what is
  // between the outer quotes as the line, and /v:off keeps a ! in it from being expanded.
  val line = if (program.endsWith(".bat", ignoreCase = true)) {
    "cmd.exe /d /v:off /s /c \"" + batchCommandLine(command) + "\""
  } else {
    windowsCommandLine(command)
  }
  return memScoped { start(line, program, null, null) }
}

/** Nothing to do: Windows runs a file by its extension, not a mode bit. */
internal actual fun makeExecutable(path: Path) = Unit

/**
 * Starts the command [line] runs, [program], waits for it and answers its exit status, with its
 * output in [output] and [error] when they are given.
 */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.start(line: String, program: String, output: HANDLE?, error: HANDLE?): Int {
  val startup = alloc<STARTUPINFOW>()
  startup.cb = sizeOf<STARTUPINFOW>().convert()
  if (output != null || error != null) {
    startup.dwFlags = STARTF_USESTDHANDLES.convert()
    startup.hStdInput = GetStdHandle(STD_INPUT_HANDLE)
    startup.hStdOutput = output ?: GetStdHandle(STD_OUTPUT_HANDLE)
    startup.hStdError = error ?: GetStdHandle(STD_ERROR_HANDLE)
  }
  val process = alloc<PROCESS_INFORMATION>()
  // CreateProcessW may write to the command line it is given, so it gets a copy of its own.
  val buffer = line.wcstr.getPointer(this)
  val started = CreateProcessW(
    null, buffer, null, null, TRUE, 0u, null, null, startup.ptr, process.ptr
  )
  if (started == 0) {
    val code = GetLastError()
    if (code == ERROR_FILE_NOT_FOUND.toUInt() || code == ERROR_PATH_NOT_FOUND.toUInt()) {
      throw ProgramNotFoundException(program)
    }
    throw IOException("Could not run $program: Windows error $code")
  }
  try {
    WaitForSingleObject(process.hProcess, INFINITE)
    val status = alloc<DWORDVar>()
    GetExitCodeProcess(process.hProcess, status.ptr)
    return status.value.toInt()
  } finally {
    CloseHandle(process.hProcess)
    CloseHandle(process.hThread)
  }
}

/** A file a started program may write as its output: created, and inherited by it. */
@OptIn(ExperimentalForeignApi::class)
private fun MemScope.createInheritable(path: Path): HANDLE {
  val security = alloc<SECURITY_ATTRIBUTES>()
  security.nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
  security.bInheritHandle = TRUE
  security.lpSecurityDescriptor = null
  val handle = CreateFileW(
    path.toString(), GENERIC_WRITE.convert(), FILE_SHARE_READ.convert(), security.ptr,
    CREATE_ALWAYS.convert(), FILE_ATTRIBUTE_NORMAL.convert(), null
  )
  if (handle == null || handle == INVALID_HANDLE_VALUE) {
    throw IOException("Cannot write $path: Windows error ${GetLastError()}")
  }
  return handle
}
