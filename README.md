# Ubuntu Terminal — Android

> Aplicación Android nativa que ejecuta **Ubuntu real** (24.04 LTS) sin root,
> con una interfaz propia construida en Jetpack Compose.

Este repositorio implementa la cadena:

```
APK Android
   ↓
libterminal.so (JNI)
   ↓
PRoot (translator estático)
   ↓
Ubuntu 24.04 rootfs REAL
   ↓
/bin/bash REAL
   ↓
comandos reales (ls, apt, git, python, gcc, …)
```

**Principio fundamental**: no se reinventa Linux. No se reinventa Ubuntu.
No se sustituyen los comandos. La aplicación solo aporta la interfaz
visual y el glue nativo necesario para que el usuario interactúe con
Ubuntu real desde Android.

---

## Requisitos de compilación

- Android Studio Koala 2024.1.1 o superior (o Gradle 8.9 + AGP 8.5.2)
- JDK 17
- Android SDK Platform 34
- Android NDK 26.3.11579264 (o compatible)
- CMake 3.22.1 (incluido con el NDK)

## Requisitos de ejecución (en el dispositivo)

- Android 9 (API 28) o superior
- arm64-v8a (~99 % de los dispositivos modernos)
- ~200 MB libres (rootfs + PRoot)
- Conexión a Internet en la **primera** ejecución (descarga del rootfs)

---

## Cómo construir

1. Abre el proyecto en Android Studio.
2. Selecciona el variant `debug`.
3. **Build → Make Project** (o `./gradlew assembleDebug`).
4. La APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

```bash
# Desde la raíz del proyecto
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

Para instalar en un dispositivo conectado:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Primera ejecución

1. Abre la app. La pantalla de bootstrap aparece automáticamente.
2. La app descarga (de forma reanudable) el rootfs oficial de Ubuntu 24.04
   desde `cdimage.ubuntu.com`.
3. Descarga también el binario estático de PRoot.
4. Extrae el rootfs a `/sdcard/Android/data/com.ubuntuterm/files/ubuntu/`.
5. Marca el rootfs como listo.
6. Se abre el terminal con un shell `/bin/bash` real dentro del rootfs.

A partir de aquí el usuario puede ejecutar:

```bash
uname -a
pwd
ls -la
apt update
apt install -y python3 git vim-tiny
python3 --version
git clone https://github.com/user/repo.git
```

Y todos los comandos se ejecutan **realmente** dentro de Ubuntu.

---

## Arquitectura

```
┌─────────────────────────────────────────────────────────┐
│                          UI                              │
│       Jetpack Compose + Canvas propio (ANSI)             │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────┐ │
│  │  Toolbar    │ │  Tabs       │ │  Terminal Canvas    │ │
│  └─────────────┘ └─────────────┘ └─────────────────────┘ │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              TerminalManager (Kotlin)                    │
│   Gestiona N sesiones (una por pestaña)                  │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              UbuntuSession (Kotlin)                     │
│   - Pide argv + envp a PRootRunner                      │
│   - Llama a NativeTerminal.nativeSpawn(...)              │
│   - Lee stdout/stderr del PTY master                     │
│   - Escribe stdin al PTY master                          │
│   - Resize, signals, wait                               │
└─────────────────────────────────────────────────────────┘
                            │ JNI
                            ▼
┌─────────────────────────────────────────────────────────┐
│              libterminal.so (C/C++)                      │
│   - openpty() — bionic libc                              │
│   - fork() / execve() — proceso hijo                    │
│   - read() / write() en PTY master                       │
│   - ioctl(TIOCSWINSZ) — resize                          │
│   - kill() / waitpid()                                  │
└─────────────────────────────────────────────────────────┘
                            │ execve
                            ▼
┌─────────────────────────────────────────────────────────┐
│              PRoot (binario estático)                    │
│   - Traductor de syscalls vía ptrace                     │
│   - --rootfs → Ubuntu                                   │
│   - --root-id  → UID 0 dentro del sandbox                │
│   - --bind     → monta /dev /proc /sys /sdcard          │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│              Ubuntu 24.04 rootfs                         │
│   /usr/bin/bash, /usr/bin/apt, /usr/bin/git, …          │
└─────────────────────────────────────────────────────────┘
```

**Sin HTTP, sin REST, sin websockets.** Toda la comunicación entre la
UI y Ubuntu es vía PTY + stdin/stdout/stderr, igual que en cualquier
emulador de terminal de escritorio.

---

## Componentes principales

| Archivo | Función |
|---------|---------|
| `cpp/terminal_jni.cpp` | Bridge JNI entre Kotlin y la PTY nativa |
| `cpp/pty_compat.c` | Thin shim sobre `openpty()` de bionic |
| `cpp/process_utils.c` | `fork()`/`execve()` con PTY adjunta |
| `terminal/NativeTerminal.kt` | Declaraciones `external fun` de los métodos JNI |
| `terminal/UbuntuSession.kt` | Sesión: lee del PTY, escribe al PTY, reaper de exit |
| `terminal/TerminalManager.kt` | Multi-sesión + selección de pestaña activa |
| `terminal/TerminalService.kt` | Foreground service para mantener viva la sesión |
| `ubuntu/PRootRunner.kt` | Construye argv y envp para `proot` |
| `bootstrap/BootstrapManager.kt` | Descarga, verifica y extrae el rootfs de Ubuntu |
| `bootstrap/PRootManager.kt` | Descarga el binario estático de PRoot |
| `ui/terminal/TerminalBuffer.kt` | Grid in-memory de celdas + cursor + scrollback |
| `ui/terminal/AnsiParser.kt` | Parser VT100/ANSI mínimo |
| `ui/terminal/TerminalView.kt` | Compose Canvas que renderiza el buffer |
| `ui/TerminalWorkspace.kt` | Toolbar + tabs + vista de terminal |
| `ui/BootstrapScreen.kt` | Pantalla de bootstrap con progreso |

---

## Decisiones técnicas

### ¿Por qué PRoot y no chroot?

chroot requiere root. Android prohíbe root sin modificar el dispositivo.
PRoot es un translator de syscalls basado en `ptrace()` que **no requiere
root** — intercepta las llamadas al sistema del proceso hijo y reescribe
las rutas absolutas para que apunten dentro de nuestro rootfs.

PRoot es el estándar de facto para ejecutar Linux userspace real sin
root en Android.

### ¿Por qué un parser VT100 propio?

La aplicación NO depende de ninguna librería externa para interpretar
ANSI. `TerminalBuffer.kt` + `AnsiParser.kt` son una implementación
propia que cubre los casos comunes (cursor, colores, scrollback,
modos SGR, OSC). Esto forma parte de la **interfaz** — el emulador
de terminal es UI, no es Ubuntu. La interpretación de comandos la
sigue haciendo `/bin/bash` real dentro de Ubuntu.

### ¿Por qué Jetpack Compose + Canvas propio?

Compose permite una UI moderna y animada. El Canvas propio para el
terminal evita el overhead de `BasicTextField` y permite un control
preciso del rendering ANSI, incluyendo el cursor parpadeante y la
selección de texto.

### ¿Por qué arm64-v8a solamente?

El 99 % de los dispositivos Android modernos son arm64. Limitar el ABI
reduce el tamaño de la APK y simplifica el build. Soporte para x86_64
(emuladores) puede añadirse fácilmente si es necesario para pruebas.

### ¿Por qué descargar el rootfs en primera ejecución y no bundle?

El rootfs de Ubuntu 24.04 base comprimido ocupa ~30 MB. Incluirlo como
asset obligaría a firmar la APK con un rootfs concreto; descargarlo en
primera ejecución permite:
- Actualizar el rootfs sin tocar la APK.
- Reducir el tamaño inicial de la APK.
- Verificar el SHA256 firmado por Ubuntu.

---

## Scripts host-side

Los scripts en `scripts/` permiten probar la cadena fuera del
dispositivo, en una máquina Linux:

| Script | Uso |
|--------|-----|
| `bootstrap-ubuntu.sh [arch]` | Descarga y extrae el rootfs de Ubuntu en el host |
| `fetch-proot.sh` | Descarga el binario estático de PRoot para arm64 |
| `smoke-test.sh [rootfs]` | Ejecuta `uname`, `pwd`, `ls`, `python3`, `git` dentro del rootfs vía PRoot |

Ejemplo completo:

```bash
cd scripts/
./bootstrap-ubuntu.sh        # crea ./ubuntu-rootfs-workspace/ubuntu-rootfs/
./smoke-test.sh              # ejecuta comandos de prueba
```

---

## Limitaciones conocidas

1. **`apt install`**: funciona, pero las operaciones que requieren
   modificar `/proc/sys` o `mount` pueden fallar por restricciones del
   kernel de Android sin root. PRoot traduce la mayoría de syscalls pero
   no todas.

2. **Servicios systemd**: no inician (Android no permite systemd). Las
   herramientas que dependen de `systemctl` no funcionan. Las que usan
   `/etc/init.d/` tampoco. La mayoría de las herramientas CLI sí
   funcionan.

3. **Rendimiento**: PRoot agrega overhead de ~1.5× a 2× en CPU para
   operaciones de syscalls intensivas. Para uso interactivo (`ls`, `vim`,
   `git`, `python`) es imperceptible. Para compilar kernels o proyectos
   grandes (gcc -j en miles de ficheros) es notable.

4. **Red**: funciona dentro del sandbox sin configuración adicional.
   `curl`, `wget`, `git clone`, `apt update`, `pip install` operan
   con normalidad.

5. **Almacenamiento**: `/sdcard` es accesible dentro de Ubuntu como
   `/sdcard`. Los archivos del usuario viven en `/home/ubuntu`.

---

## Roadmap

- [ ] Bundled rootfs como asset alternativo (sin descarga inicial)
- [ ] Soporte para x86_64 (emuladores)
- [ ] Ampliar cobertura del parser VT100 propio (bracketed paste, mouse, alt screen)
- [ ] Temas personalizables (dark/light/custom)
- [ ] Explorador de archivos integrado
- [ ] Atajos de teclado configurables
- [ ] Integración de IA (sección 18) sobre el mismo Ubuntu real

---

## Licencia

MIT. El rootfs de Ubuntu y el binario de PRoot mantienen sus propias
licencias (GPL y MIT respectivamente).
