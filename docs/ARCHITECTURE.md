# Arquitectura técnica

## Visión general

La aplicación se compone de **4 capas** claramente separadas:

```
┌────────────────────────────────────────────────────────────┐
│ 1. UI LAYER                                                 │
│    Jetpack Compose + Canvas (terminal renderer)             │
│    MainActivity.kt, ui/                                     │
├────────────────────────────────────────────────────────────┤
│ 2. SESSION LAYER                                            │
│    TerminalManager + UbuntuSession                          │
│    terminal/                                               │
├────────────────────────────────────────────────────────────┤
│ 3. NATIVE LAYER                                            │
│    libterminal.so (JNI: openpty, fork, execve)              │
│    cpp/                                                    │
├────────────────────────────────────────────────────────────┤
│ 4. UBUNTU LAYER                                            │
│    PRoot + Ubuntu 24.04 rootfs (real)                      │
│    bootstrap/, ubuntu/PRootRunner.kt                       │
└────────────────────────────────────────────────────────────┘
```

Cada capa tiene una única responsabilidad y se comunica con la inferior
mediante un mecanismo explícito y auditable.

---

## 1. UI Layer

### Componentes

| Componente | Responsabilidad |
|-----------|-----------------|
| `MainActivity.kt` | Punto de entrada, observa el estado de bootstrap |
| `BootstrapScreen.kt` | Pantalla de descarga/configuración inicial |
| `TerminalWorkspace.kt` | Toolbar + pestañas + vista del terminal activo |
| `TerminalView.kt` | Compose Canvas que renderiza el buffer ANSI |
| `TerminalBuffer.kt` | Grid in-memory de celdas + cursor + scrollback |
| `AnsiParser.kt` | Parser VT100/xterm mínimo |
| `theme/Color.kt` | Paleta Ubuntu (Aubergine + Orange) |
| `theme/Theme.kt` | MaterialTheme dark color scheme |

### Comunicación

- La UI nunca toca ni PRoot ni Ubuntu directamente.
- Toda interacción pasa por `TerminalViewModel → TerminalManager → UbuntuSession`.
- El flujo de salida es: `PTY master fd → NativeTerminal.nativeRead() → UbuntuSession.output (SharedFlow) → TerminalView.collectLatest { buffer.write(it) } → Canvas recompose`.
- El flujo de entrada es: `key event → TerminalView → session.write(bytes) → NativeTerminal.nativeWrite() → PTY master fd → bash`.

### Por qué Canvas propio en lugar de Android TextView

- Rendimiento: renderizar 80×24 celdas con atributos (color, bold, etc.)
  en un `Text` requiere construir `AnnotatedString` en cada frame,
  lo cual es caro cuando hay mucho scroll.
- Control: el cursor parpadeante, la selección rectangular, el dibujo
  de fondos por celda y los modos inversos requieren control pixel-level.
- Extensibilidad: añadir nuevos modos visuales (ej. resaltado de
  ocurrencias de búsqueda, zonas de error con subrayado ondulado) es
  trivial con un `DrawScope`.

---

## 2. Session Layer

### `TerminalManager`

- Mantiene una lista observable (`StateFlow<List<UbuntuSession>>`) de
  sesiones vivas.
- Expone `activeSessionId` para que la UI sepa qué terminal mostrar.
- Métodos: `openSession`, `setActive`, `closeSession`, `closeAll`.

### `UbuntuSession`

- Envoltorio de alto nivel sobre un `handle` (jlong que empaqueta
  `pid` + `master_fd`).
- Almacena:
  - `output: SharedFlow<ByteArray>` — bytes crudos del PTY.
  - `state: StateFlow<SessionState>` — Idle/Starting/Running/Exited/Closed/Failed.
  - `exitCode: StateFlow<Int?>` — código de salida cuando termina.
- Lanza dos corrutinas:
  1. **Reader**: loop que llama `nativeRead()` en bloques de 8 KB y
     emite cada chunk a `output`.
  2. **Reaper**: poll de `nativeWaitExit(handle, blocking=false)` cada
     250 ms; cuando el hijo termina, actualiza `exitCode` y cierra.
- API pública: `write(byteArray)`, `sendEnter`, `sendCtrlC/D/Z`,
  `kill(force)`, `resize(cols, rows)`, `close`.

### `TerminalService`

- Foreground service que mantiene la sesión viva en background.
- Sin él, Android mataría el proceso hijo cuando el usuario cambia
  de app, perdiendo cualquier comando en curso (`apt install`, `git
  clone`, `gcc`, etc.).
- Notificación de baja prioridad con el número de sesiones activas.

---

## 3. Native Layer

### `libterminal.so`

Compilado vía CMake desde:

- `cpp/terminal_jni.cpp` — entry points JNI (`nativeSpawn`, `nativeRead`,
  `nativeWrite`, `nativeSetSize`, `nativeSendSignal`, `nativeWaitExit`,
  `nativeClose`).
- `cpp/pty_compat.c` — wrapper sobre `openpty()` de bionic y
  `ioctl(TIOCSWINSZ)` para resize.
- `cpp/process_utils.c` — `spawn_with_pty()` que hace `fork()` + en
  el hijo `setsid()` + `dup2(slave, 0/1/2)` + `execve()`, y en el
  padre devuelve el `master_fd`.

### Flujo de spawn

```
nativeSpawn(cwd, argv, envp, cols, rows)
        ↓
pty_compat_openpty(&master, &slave, cols, rows)
        ↓
fork()
        ├── child:
        │     setsid()
        │     open(slave_path)  → controlling tty
        │     dup2(slave, 0/1/2)
        │     close(master)
        │     chdir(cwd)
        │     close all fds > 2
        │     execve(argv[0], argv, envp)
        │     _exit(127) si falla
        │
        └── parent:
              close(slave)
              fcntl(master, F_SETFL, O_NONBLOCK)
              return ((master_fd << 32) | pid)
```

### Comunicación UI ↔ proceso hijo

**Solo mecanismos nativos**: PTY, pipes, señales, `waitpid`.

| Operación | Mecanismo |
|-----------|-----------|
| stdin (UI → bash)  | `write(master_fd, buf, len)` |
| stdout (bash → UI) | `read(master_fd, buf, len)` (non-blocking) |
| resize              | `ioctl(master_fd, TIOCSWINSZ, &ws)` |
| Ctrl+C              | `write(master_fd, "\x03", 1)` o `kill(pid, SIGINT)` |
| Ctrl+D              | `write(master_fd, "\x04", 1)` |
| Exit code           | `waitpid(pid, &status, WNOHANG)` |

**Sin HTTP. Sin REST. Sin websockets. Sin AIDL. Sin Binder.**
La comunicación es bit-for-bit idéntica a la de cualquier terminal
de escritorio (gnome-terminal, xterm, alacritty, kitty, …).

---

## 4. Ubuntu Layer

### `PRootRunner`

Construye el argv para invocar PRoot:

```
proot
  --rootfs=<rootfs_path>
  --root-id                  # UID 0 dentro del sandbox (para apt)
  --link2symlink             # hardlinks funcionan
  --kill-on-exit             # no deja huérfanos
  --cwd=/home/ubuntu
  --hostname=ubuntuterm
  --bind=/dev
  --bind=/dev/urandom:/dev/random
  --bind=/proc
  --bind=/sys
  --bind=/sdcard:/sdcard     # Android storage accesible en Ubuntu
  --
  /bin/bash -l               # login shell
```

### `BootstrapManager`

1. Comprueba si `FileLocations.readyMarker` existe.
2. Si no, descarga `ubuntu-base-24.04.1-base-arm64.tar.gz` desde
   `cdimage.ubuntu.com`.
3. Verifica SHA256 (TODO: bundlear el hash en build time).
4. Extrae el tarball (gzip + tar) en `FileLocations.ubuntuRootDir`.
5. Configura `/etc/resolv.conf`, `/etc/hosts`, `/etc/hostname`.
6. Crea usuario `ubuntu` UID 1000 en `/etc/passwd` y `/etc/group`.
7. Escribe `.bashrc` con PS1, PATH, LANG, TERM.
8. Marca `.ubuntuterm_ready`.

### `PRootManager`

1. Comprueba si `FileLocations.prootBinary` existe y es ejecutable.
2. Si no, descarga desde el único source oficial:
   - GitHub: `proot/proot` releases (`proot-v5.1.0-arm64-static`).
3. `chmod +x` + verificación.

No existe ningún fallback a repositorios de Termux.

---

## Persistencia

| Ruta | Contenido | Persistencia |
|------|-----------|---------------|
| `/sdcard/Android/data/com.ubuntuterm/files/ubuntu/` | rootfs de Ubuntu | Sí (sobrevive upgrades) |
| `/sdcard/Android/data/com.ubuntuterm/files/proot/proot` | PRoot estático | Sí |
| `/sdcard/Android/data/com.ubuntuterm/files/sessions/` | estado por sesión (history, scrollback) | Sí |
| `/sdcard/Android/data/com.ubuntuterm/files/cache/` | tarball temporal | No (limpiable) |

Todo vive bajo el storage scoped del app, lo que significa:
- No requiere `MANAGE_EXTERNAL_STORAGE`.
- Se borra automáticamente al desinstalar.
- Sobrevive actualizaciones de la app.

---

## Manejo de errores

### Fallos de bootstrap

- **Sin red**: el `BootstrapManager.Result.Failed` lleva el mensaje y
  la UI muestra botón de **Retry**.
- **Tarball corrupto**: el SHA256 check falla y se borra el tarball
  parcial.
- **Espacio insuficiente**: el `IOException` de `FileOutputStream`
  se propaga como `Failed`.

### Fallos de sesión

- **execve falla**: el hijo escribe el error al PTY y `_exit(127)`.
  El reaper lo detecta y emite `SessionState.Exited(127)`.
- **Proceso muerto**: `nativeRead` devuelve -2 (EOF) y la sesión se
  cierra limpiamente.
- **ImInput bloqueado**: `nativeWrite` puede devolver 0 si el pipe
  está lleno; el caller reintenta en el siguiente frame.

---

## Optimizaciones futuras

1. **Ampliar el AnsiParser propio** para cubrir más secuencias VT100
   (bracketed paste, mouse reports, alt screen, DECSET/DECSET).
2. **Bundled rootfs** como alternativa para instalaciones offline.
3. **Compresión de scrollback** con run-length encoding para reducir
   memoria en sesiones largas.
4. **Render de texto con `TextMeasurer`** en lugar de `Paint.drawText`
   para soporte correcto de emoji y combining characters.
5. **Soporte para bracketed paste mode** (ESC [ ? 2004 h).
