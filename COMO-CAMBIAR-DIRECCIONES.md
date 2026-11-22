# Cómo cambiar las direcciones de la app "Partidos"

Los sitios cambian de dirección seguido. La app lee su lista de direcciones de un
**gist secreto de GitHub** cada vez que se abre, así que se arregla desde el celular,
sin recompilar.

## Desde el celular

1. Abrí en el navegador: https://gist.github.com/NicoDiddi11/9ad5c6ecb5d0ff48eb9860c2e73f197b
   (tenés que estar con tu cuenta de GitHub, NicoDiddi11).
2. Tocá **Edit** (el lápiz).
3. Cambiá **solo lo que está después del `=`** en la línea del sitio que se mudó. Ejemplo:

   ```
   principal MadPlay77 = https://NUEVA-DIRECCION/es
   ```

4. **Update secret gist**.
5. Cerrá y abrí la app en la tele. GitHub tarda **hasta 5 minutos** en entregar la versión nueva.

## Reglas del archivo

- Una dirección por línea: `grupo  nombre  =  https://…`
- `principal` = de donde salen resultados, minuto y escudos. **La primera es la preferida.**
  Tiene que haber al menos una.
- `vip` = la lista complementaria (VIPBox/VIPRow). Puede no haber ninguna.
- `argentina` = los sitios del grupo **Argentina**, en orden de importancia (el primero manda
  al unir: su nombre del partido, su hora y su primer canal).
- Las líneas que empiezan con `#` se ignoran.
- Para agregar un espejo nuevo, sumá una línea. Para sacar uno, borrala.

## Grupo Argentina

Hoy el gist **no tiene** líneas `argentina`, así que la app usa las que trae adentro:

```
argentina TVLibre = https://tvlibre.st/agenda/
argentina DeportesTVHD = https://deportestvhd.me/
argentina AngulismoTV = https://angulismotv.pages.dev/transmision/
argentina AngulismoTV2 = https://99fcafcf.angulismotv.pages.dev/transmision
argentina NowFutx = https://nowfutx.xyz/vivo/
```

- Si un sitio argentino se muda, copiá estas líneas al gist y cambiá la dirección. **Ojo: si
  ponés alguna línea `argentina`, la app usa solo las del gist**, así que van todas.
- **El nombre no se cambia**: dice cómo se lee cada sitio (TVLibre es una página, DeportesTVHD un
  archivo `eventos.js`, AngulismoTV un JSON, NowFutx un canal fijo).
- `AngulismoTV2` es la misma página en otro dominio: se usa solo si la primera no responde.
- Los partidos de AngulismoTV salen de un archivo en GitHub; si ese cambia de lugar:
  `argentina AngulismoDatos = https://…/datos.json` (de esa dirección solo se leen datos,
  nunca se abre en el navegador).
- A diferencia de MadPlay77, estos sitios **no** aprenden solos una mudanza: hay que tocar el gist.

## Qué pasa si algo sale mal

- **Si escribís mal el archivo** (por ejemplo, borrás todas las `principal`), la app lo
  rechaza y sigue con la última lista buena que tenía guardada.
- **Si GitHub no responde**, la app usa la última lista guardada.
  Y si nunca pudo leerla, usa las direcciones que vienen adentro del APK.
- **Si un sitio se muda con redirección de servidor** (la dirección vieja manda sola a la
  nueva), la app se da cuenta, verifica que en la nueva haya partidos y la recuerda. En ese
  caso no hace falta tocar nada. Si después editás el gist, manda lo que dice el gist.

## Pruebas (para Claude / por adb)

```
adb shell am start -n ar.partidostv/.MainActivity --es configPrueba "principal X = https://…"
```

Esto usa esa lista sin guardarla y sin leer el gist. Otros parámetros: `--ei sitio N`,
`--ei sitioVip N`, `--es grupo argentina|internacional` (abre en ese grupo y lo deja guardado)
y `--ez volcar true` (guarda las listas leídas en `files/`, que se bajan con
`adb exec-out run-as ar.partidostv cat files/principal.json`; la del grupo Argentina es
`files/argentina.json`).

Con la pantalla de la tele apagada, Android pausa la app y la lectura se descarta (a propósito:
no se carga nada si nadie mira). El volcado de `argentina.json` igual se guarda.
