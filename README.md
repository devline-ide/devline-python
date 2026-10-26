# Python Runner

Default-enabled, independently packaged IDE extension (`devline.python`). Requires an installed Python 3 interpreter. Python and script dependencies are not downloaded automatically.

- Run a `.py` file from its editor toolbar, tab menu or explorer menu. Dirty open scripts are saved through the normal conflict-aware save workflow before execution.
- A dedicated integrated-terminal tab supports stdin, live UTF-8 output, exit codes, stopping and restarting. Restart repeats the previous launch arguments. Closing the project, disabling the extension and shutting down stop its processes and descendants.
- Configure Python under **Settings > Extensions > Python**. The extension declares this settings webview itself.
- Automatic interpreter selection: project `.venv`, project `venv`, Python on PATH, then the Windows `py -3` launcher. Windows Store execution aliases are skipped.
- Global configuration and complete per-project overrides are stored under the host profile's `python-runner/settings.json`. They are never written to the project. Resetting a project override restores global settings.
- Working directory defaults to the project root; arguments are a JSON string array and environment variables a JSON string map. Executables receive argv directly without a shell. Interactive output remains readable after Stop or extension disable.

Build: `./gradlew.bat :devline-python:extensionArchive`. Verify the real interpreter/PTY, archive install, settings, owner guards and process cleanup with `./gradlew.bat :devline-python:pythonCheck`.

The shell consumes the generic `fileExtensions` command declaration; Python-specific configuration and interpreter selection live entirely in this module. Host capabilities: `editor.file-commands`, `terminal.programs`, `ui.webviews`, `ui.settings`.
