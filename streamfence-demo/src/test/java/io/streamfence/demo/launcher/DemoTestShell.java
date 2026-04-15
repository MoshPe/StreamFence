package io.streamfence.demo.launcher;

import java.util.List;

final class DemoTestShell {

    private DemoTestShell() {
    }

    static List<String> echoStdIn() {
        if (isWindows()) {
            return List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "while (($line = [Console]::In.ReadLine()) -ne $null) { Write-Output $line }");
        }
        return List.of(
                "sh",
                "-lc",
                "while IFS= read -r line; do printf '%s\\n' \"$line\"; done");
    }

    static List<String> consumeStdInAndStayAlive() {
        if (isWindows()) {
            return List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "while (($line = [Console]::In.ReadLine()) -ne $null) { Start-Sleep -Milliseconds 100 } Start-Sleep -Seconds 15");
        }
        return List.of(
                "sh",
                "-lc",
                "while IFS= read -r line; do sleep 0.1; done; sleep 15");
    }

    static List<String> silentShortLived() {
        if (isWindows()) {
            return List.of(
                    "powershell.exe",
                    "-NoProfile",
                    "-NonInteractive",
                    "-Command",
                    "Start-Sleep -Milliseconds 750");
        }
        return List.of(
                "sh",
                "-lc",
                "sleep 1");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
