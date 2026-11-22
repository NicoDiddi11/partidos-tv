# Partidos (Android TV)

App personal para Android TV: junta los partidos de varios sitios, los ordena por deporte y liga,
y abre el elegido **en el navegador de la tele**. La app no reproduce video ni muestra el sitio
adentro: solo lista y abre.

Dos grupos, arriba a la izquierda:

- **Internacional**: MadPlay77 / FCTV33 (principal) y VIPBox / VIPRow (complementaria).
- **Argentina**: TVLibre (principal), DeportesTVHD, AngulismoTV y el grupo «LPF Play Argentina» de EsporteVIP
  (Ascenso: Federal A, Primera C…; esos partidos no tienen página propia, el botón abre la portada del sitio).
  Se unen sin repetir; OK abre el primer canal y → recorre los demás (`+N` muestra todos).
  Las opciones que piden extensión de PC no se muestran (en la tele no andan).

## Estructura

| Archivo | Qué hace |
| --- | --- |
| `app/src/main/java/ar/partidostv/MainActivity.java` | Lectura de los sitios (navegador oculto para MadPlay77, texto directo para VIPBox/VIPRow y los argentinos), direcciones desde el gist, mudanzas, mapa de ligas |
| `app/src/main/assets/ui.html` | Interfaz para el control remoto: unión sin repetidos, estado en vivo, banderas, escudos, filtros, favoritos |
| `COMO-CAMBIAR-DIRECCIONES.md` | Cómo cambiar las direcciones de los sitios desde el celular |
| `ver-tele.cmd` | Espejo de la tele en la PC (scrcpy) |

## Compilar e instalar

Requiere Java 17 y el SDK de Android 35.

```
gradlew assembleDebug
adb connect 192.168.0.181:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Subir `versionCode` en `app/build.gradle` en cada versión.

## Firma

La app se firma con la clave guardada en `OneDrive\Documentos\partidos-tv-firma\partidos-tv.keystore`
(no está en el repositorio). Es la misma con la que se instaló por primera vez: firmar con otra
obliga a desinstalar y se pierden favoritos y listas guardadas.
