# Principio de separación: la app NO es Ubuntu

> La pregunta correcta siempre es:
>
> *«¿Cómo hacemos que Ubuntu real haga esto y cómo hacemos que nuestra
> aplicación lo presente al usuario?»*
>
> No:
>
> *«¿Cómo podemos programar nuestra propia versión de esto?»*

---

## Frontera conceptual

```
            NUESTRA APK
                 │
                 │
          NUESTRO DISEÑO
                 │
                 ▼
           UBUNTU REAL
                 │
        ┌────────┼────────┐
        ▼        ▼        ▼
       APT      Bash     Linux
        │
        ▼
   Repositorios Ubuntu
        │
        ▼
    Paquetes Ubuntu
```

**Nosotros diseñamos la aplicación. Ubuntu proporciona el ecosistema Linux.**

---

## Qué construye la app

| Componente | Función | Reinventa Ubuntu? |
|-----------|---------|-------------------|
| `libterminal.so` | Glue JNI: openpty + fork + execve | ❌ No |
| `BootstrapManager` | Descarga Ubuntu 24.04 oficial | ❌ No |
| `PRootManager` | Descarga PRoot estático | ❌ No |
| `PRootRunner` | Construye argv de PRoot | ❌ No |
| `UbuntuSession` | Lee/escribe bytes del PTY | ❌ No |
| `TerminalManager` | Multi-sesión | ❌ No |
| `TerminalBuffer` + `AnsiParser` | Emulador de terminal (como xterm) | ❌ No — es UI |
| `TerminalView` | Canvas que renderiza bytes | ❌ No — es UI |
| `BootstrapScreen` | Pantalla de progreso | ❌ No — es UI |

**Ninguno de estos componentes** reimplementa un comando Linux, ni un
gestor de paquetes, ni un shell, ni un filesystem.

---

## Qué NO construye la app

| Elemento | Por qué no |
|----------|------------|
| Repositorio de paquetes propio | Ubuntu ya tiene `apt` |
| Gestor de paquetes propio | Ubuntu ya tiene `apt-get` |
| Shell propio | Ubuntu ya tiene `/bin/bash` |
| Comandos propios (`myapp-*`) | Ubuntu ya tiene `ls`, `cp`, `git`, etc. |
| Filesystem inventado | Se usa el rootfs real de Ubuntu |
| API REST local | Comunicación solo vía PTY |
| Terminal simulado | Se usa PTY real del kernel Linux |
| Distribución Linux propia | Se usa Ubuntu 24.04 oficial |

---

## Verificación por flujo de datos

Cuando el usuario escribe `apt install git` en la UI:

1. **UI**: `TerminalView` captura las teclas como bytes.
2. **Sesión**: `UbuntuSession.write(bytes)` las envía al PTY.
3. **JNI**: `NativeTerminal.nativeWrite(handle, bytes, ...)` escribe
   al `master_fd`.
4. **Kernel Linux**: el driver de PTY entrega los bytes al proceso
   hijo conectado al `slave_fd`.
5. **PRoot** (proceso hijo): recibe los bytes en su stdin.
6. **PRoot** traduce las syscalls del proceso hijo.
7. **bash** (dentro de Ubuntu real) lee los bytes, interpreta
   `apt install git` y ejecuta el binario `apt` real.
8. **apt** (binario real de Ubuntu) se conecta a `archive.ubuntu.com`,
   descarga el paquete `git` real y lo instala en el rootfs real.
9. La salida de `apt` vuelve por la cadena inversa hasta el Canvas.

**En ningún punto del camino** la app:
- Parsea `apt install git`.
- Sabe que el usuario escribió `apt`.
- Decide qué hacer con el comando.
- Tiene un catálogo de paquetes.

La app solo ve **bytes crudos** en ambas direcciones. Esto es exactamente
lo mismo que hace gnome-terminal, xterm, alacritty, kitty o cualquier
emulador de terminal de escritorio.

---

## Diferencia entre "emulador de terminal" y "shell"

| Concepto | Qué hace | Lo hace la app? |
|----------|----------|------------------|
| **Emulador de terminal** | Renderiza caracteres ANSI, gestiona cursor, scrollback, colores | ✅ Sí (`TerminalView`) |
| **Shell** | Interpreta comandos, expande variables, ejecuta binarios | ❌ No (lo hace `/bin/bash` real) |
| **Comando** | Implementa una función (`ls`, `cp`, `git`) | ❌ No (son binarios reales de Ubuntu) |
| **Paquete** | Software instalable | ❌ No (los instala `apt` real) |
| **Repositorio** | Catálogo de paquetes | ❌ No (son los de Ubuntu) |

Esta separación es la misma que existe en cualquier Linux de escritorio:
gnome-terminal (emulador) + bash (shell) + binarios (comandos) + apt
(paquetes). Aquí simplemente movemos el emulador a Android, pero el
shell, los comandos y los paquetes siguen siendo los de Ubuntu.

---

## Implicaciones para futuras features

### Agente de IA (sección 18 de la spec)

Cuando se integre un agente de IA:

- ❌ **No** crear una API artificial de comandos.
- ❌ **No** inventar un protocolo especial.
- ✅ El agente debe conectarse **al mismo Ubuntu real** que el usuario.
- ✅ El agente escribe a un PTY, igual que un humano.
- ✅ El agente ejecuta `git`, `python`, `apt`, `gcc`, `gradle` reales.

```
Usuario ──┐
          ├──► Ubuntu real ──► herramientas reales
Agente ───┘         │
                    └── mismo ecosistema, misma fuente de verdad
```

### Explorador de archivos

- ❌ **No** crear un filesystem virtual.
- ✅ Mostrar el filesystem de Ubuntu real (`/home/ubuntu`, `/etc`, etc.).
- ✅ Operaciones (`mkdir`, `rm`, `mv`) ejecutadas por los binarios
  reales dentro de Ubuntu, no por código propio de la app.

### Atajos de teclado personalizados

- ❌ **No** crear una sintaxis de comandos propia.
- ✅ Atajos solo traducen a secuencias que el shell entiende
  (Ctrl+C → `\x03`, Tab → `\t`, etc.).

---

## Detección de desviación

Si durante el desarrollo descubres que estás construyendo:

- Un repositorio de paquetes propio → **DETENTE**. Delega a `apt`.
- Un gestor de paquetes propio → **DETENTE**. Delega a `apt-get`.
- Comandos propios para reemplazar comandos Ubuntu → **DETENTE**.
- Un shell propio para reemplazar Bash → **DETENTE**.
- Un filesystem que imita Linux → **DETENTE**.
- Un sistema que simula Ubuntu → **DETENTE**.

Eso significa que te estás desviando del objetivo.

La pregunta correcta siempre es:

> *«¿Cómo hacemos que Ubuntu real haga esto y cómo hacemos que nuestra
> aplicación lo presente al usuario?»*
