# Cómo construir y probar

## Pre-requisitos

### Software

| Herramienta | Versión mínima | Verificación |
|------------|----------------|--------------|
| Android Studio | Koala 2024.1.1 | Help → About |
| JDK | 17 | `java -version` |
| Android SDK Platform | 34 | SDK Manager |
| Android NDK | 26.x | SDK Manager → SDK Tools |
| CMake | 3.22.1 | (incluido con NDK) |
| Gradle | 8.9 | (incluido con el wrapper) |

### Hardware

- Un dispositivo arm64-v8a con Android 9+ (recomendado), **o**
- Un emulador x86_64 (requiere añadir soporte x86_64 — ver abajo).

---

## 1. Compilar la APK

### Desde Android Studio

1. `File → Open` → selecciona la carpeta `ubuntu-terminal/`.
2. Espera a que Gradle sincronice (primera vez ~3 min).
3. Copia `local.properties.template` a `local.properties` y edita el `sdk.dir`.
4. Selecciona el build variant `debug` en la barra inferior izquierda.
5. `Build → Make Project` (o `Ctrl+F9`).
6. La APK se genera en `app/build/outputs/apk/debug/app-debug.apk`.

### Desde línea de comandos

```bash
cd ubuntu-terminal/

# 1. Crea local.properties apuntando a tu SDK
cp local.properties.template local.properties
echo "sdk.dir=/ruta/a/tu/Android/Sdk" > local.properties

# 2. Construye
./gradlew assembleDebug
# Resultado:
#   app/build/outputs/apk/debug/app-debug.apk
```

Para una release firmada:

```bash
./gradlew assembleRelease \
    -Pandroid.injected.signing.store.file=/path/to/keystore.jks \
    -Pandroid.injected.signing.store.password=*** \
    -Pandroid.injected.signing.key.alias=ubuntu \
    -Pandroid.injected.signing.key.password=***
```

---

## 2. Instalar en dispositivo

```bash
# Conecta el dispositivo por USB con depuración activada
adb devices

# Instala
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Lanza la app
adb shell am start -n com.ubuntuterm.debug/com.ubuntuterm.MainActivity
```

---

## 3. Primera ejecución en el dispositivo

1. Abre la app. Aparece la pantalla de **bootstrap**.
2. La app descarga (~30 MB) el rootfs oficial de Ubuntu 24.04 arm64
   desde `cdimage.ubuntu.com`.
3. Descarga (~5 MB) el binario estático de PRoot.
4. Extrae el rootfs a `/sdcard/Android/data/com.ubuntuterm/files/ubuntu/`.
5. Marca `.ubuntuterm_ready`.
6. Se abre el terminal con `/bin/bash` real.

A partir de aquí el usuario puede escribir cualquier comando Ubuntu:

```bash
uname -a
pwd
ls -la
apt update
apt install -y python3 git vim-tiny
python3 --version
git clone https://github.com/user/repo.git
```

**Todos los comandos se ejecutan dentro de Ubuntu real.**

---

## 4. Probar la cadena en el host (sin dispositivo Android)

Los scripts en `scripts/` permiten verificar que el rootfs de Ubuntu
descargado funciona correctamente vía PRoot, **sin necesidad de un
dispositivo Android**. Útil para depurar problemas del rootfs antes
de empaquetar la APK.

```bash
cd scripts/

# 1. Descarga y extrae el rootfs de Ubuntu 24.04 arm64
./bootstrap-ubuntu.sh aarch64
#   → crea ./ubuntu-rootfs-workspace/ubuntu-rootfs/

# 2. (Opcional) Descarga el binario de PRoot
./fetch-proot.sh
#   → crea ./proot-static/proot-arm64

# 3. Smoke tests: uname, pwd, ls, mkdir, touch, python3, git
./smoke-test.sh ./ubuntu-rootfs-workspace/ubuntu-rootfs
```

Salida esperada del smoke test:

```
==> Running smoke tests inside ./ubuntu-rootfs-workspace/ubuntu-rootfs

$ uname -a
Linux ubuntuterm 6.x.x ... aarch64 GNU/Linux

$ pwd
/home/ubuntu

$ ls -la
total 8
drwxr-xr-x 2 ubuntu ubuntu 4096 ... .

$ echo Hello
Hello

$ mkdir -p test && cd test && touch file.txt && ls -la
total 0
-rw-r--r-- 1 ubuntu ubuntu 0 ... file.txt

==> Smoke tests complete
```

Si el smoke test pasa en el host, la misma cadena funcionará en el
dispositivo (la única diferencia es que en el dispositivo PRoot se
bundea dentro de la APK en lugar de usar el del sistema).

---

## 5. Logs

Una vez instalada la app, los logs son visibles vía:

```bash
adb logcat -s terminal_jni:V process_utils:V UbuntuSession:V \
            TerminalManager:V BootstrapManager:V PRootManager:V TerminalService:V
```

Mensajes clave:

| Tag | Mensaje | Significado |
|-----|---------|-------------|
| `terminal_jni` | `Spawned child pid=X master_fd=Y` | spawn OK |
| `process_utils` | `Spawned child pid=X master_fd=Y slave=/dev/pts/N` | PTY abierta |
| `BootstrapManager` | `Downloading https://cdimage.ubuntu.com/...` | descarga iniciada |
| `PRootManager` | `PRoot installed at ... (X bytes)` | PRoot listo |
| `UbuntuSession` | `Spawned pid for session X (handle=0x...)` | sesión activa |

---

## 6. Troubleshooting

### `UnsatisfiedLinkError: No implementation found for nativeSpawn`

- Verifica que `libterminal.so` está en
  `app/build/intermediates/cxx/Debug/<hash>/obj/arm64-v8a/libterminal.so`.
- Si no existe, ejecuta `./gradlew :app:externalNativeBuildDebug` para
  forzar el build nativo.

### `Bootstrap failed: HTTP 403`

- cdimage.ubuntu.com puede rate-limitar. Espera 1 minuto y reintenta.

### PRoot crash con `ptrace: operation not permitted`

- Algunos fabricantes (MIUI, EMUI) deshabilitan `ptrace` para apps
  sin root. Verifica con:
  ```bash
  adb shell getprop ro.kernel.yama.ptrace_scope
  ```
  Si es `1` o `2`, PRoot no funcionará sin root.
- Workaround: el usuario debe habilitar `Developer Options → Mi Unlock Status`
  o equivalente, o usar un dispositivo menos restrictivo.

### `apt update` falla con `Temporary failure resolving 'archive.ubuntu.com'`

- Verifica `/etc/resolv.conf` dentro del rootfs:
  ```bash
  cat /sdcard/Android/data/com.ubuntuterm/files/ubuntu/etc/resolv.conf
  ```
- Debe contener al menos `nameserver 1.1.1.1`.

### La app se cierra al cambiar de orientación

- La `MainActivity` tiene `android:configChanges="orientation|..."`
  para evitar recrearse. Si aun así se cierra, revisa que el
  `TerminalViewModel` no dependa del `Context` de Activity.

---

## 7. Soporte para x86_64 (emuladores)

Por defecto la APK solo compila para `arm64-v8a`. Para probar en un
emulador x86_64:

1. Edita `app/build.gradle.kts`:
   ```kotlin
   ndk {
       abiFilters += listOf("arm64-v8a", "x86_64")
   }
   ```
2. Rebuild.
3. Necesitarás un rootfs x86_64 en lugar de arm64. En `BootstrapManager.kt`
   cambia `rootfsUrl` para usar `amd64` en lugar de `arm64`.
4. El binario PRoot debe ser el de x86_64 (`proot-v5.1.0-x86_64-static`).

---

## 8. Estructura del proyecto

```
ubuntu-terminal/
├── README.md
├── BUILD.md                    ← este archivo
├── docs/
│   └── ARCHITECTURE.md
├── scripts/
│   ├── bootstrap-ubuntu.sh    ← descarga Ubuntu en host
│   ├── fetch-proot.sh          ← descarga PRoot en host
│   └── smoke-test.sh           ← tests básicos del rootfs
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/
├── local.properties.template
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── cpp/                 ← libterminal.so
        │   ├── CMakeLists.txt
        │   ├── terminal_jni.cpp
        │   ├── pty_compat.c
        │   ├── pty_compat.h
        │   ├── process_utils.c
        │   └── process_utils.h
        ├── java/com/ubuntuterm/
        │   ├── UbuntuTerminalApp.kt
        │   ├── MainActivity.kt
        │   ├── bootstrap/
        │   │   ├── BootstrapManager.kt    ← descarga Ubuntu real
        │   │   └── PRootManager.kt         ← descarga PRoot real
        │   ├── ubuntu/
        │   │   └── PRootRunner.kt         ← construye argv de PRoot
        │   ├── terminal/
        │   │   ├── NativeTerminal.kt       ← bridge JNI
        │   │   ├── UbuntuSession.kt        ← sesión PTY
        │   │   ├── TerminalManager.kt      ← multi-sesión
        │   │   └── TerminalService.kt      ← foreground service
        │   ├── ui/
        │   │   ├── theme/
        │   │   │   ├── Color.kt
        │   │   │   └── Theme.kt
        │   │   ├── terminal/
        │   │   │   ├── TerminalBuffer.kt   ← grid in-memory
        │   │   │   ├── AnsiParser.kt       ← parser VT100 (UI)
        │   │   │   └── TerminalView.kt     ← Compose Canvas
        │   │   ├── BootstrapScreen.kt
        │   │   ├── TerminalWorkspace.kt
        │   │   └── TerminalViewModel.kt
        │   └── util/
        │       ├── FileLocations.kt
        │       └── IOUtils.kt
        └── res/
            ├── values/
            │   ├── colors.xml
            │   ├── strings.xml
            │   └── themes.xml
            ├── values-night/
            │   └── strings.xml
            ├── drawable/
            │   ├── ic_launcher_background.xml
            │   └── ic_launcher_foreground.xml
            ├── mipmap-anydpi-v26/
            │   ├── ic_launcher.xml
            │   └── ic_launcher_round.xml
            └── xml/
                ├── backup_rules.xml
                └── data_extraction_rules.xml
```

---

## 9. Verificación de cumplimiento de la spec

| Regla de la spec | Cumplimiento |
|------------------|--------------|
| Ubuntu real (no simulado) | ✅ Rootfs oficial de cdimage.ubuntu.com |
| Sin reinventar Linux | ✅ Se usa PRoot, no se reimplementa |
| Sin comandos propios | ✅ La app no conoce qué ejecuta el usuario |
| Sin gestor de paquetes propio | ✅ Se usa `apt` de Ubuntu |
| Sin repositorio propio | ✅ Se usan los repos de Ubuntu |
| Sin shell propio | ✅ Se usa `/bin/bash` de Ubuntu |
| Sin filesystem inventado | ✅ Se usa el rootfs real de Ubuntu |
| Sin REST/HTTP local | ✅ Solo PTY + fork + execve |
| Sin Termux | ✅ Sin dependencia, sin URL, sin librería, sin referencia |
| Sin root como requisito | ✅ PRoot funciona sin root |
| Diseño visual propio | ✅ Compose + Canvas personalizado |
| PTY real | ✅ openpty + ioctl(TIOCSWINSZ) |
| ANSI colors | ✅ Parser VT100 |
| Ctrl+C, Ctrl+D, Ctrl+Z | ✅ Vía PTY + señales |
| Persistencia entre sesiones | ✅ Rootfs en scoped storage |
| Comunicación local no REST | ✅ JNI + PTY |
| libterminal.so no es Ubuntu | ✅ Solo glue nativo |
