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
- Las líneas que empiezan con `#` se ignoran.
- Para agregar un espejo nuevo, sumá una línea. Para sacar uno, borrala.

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
`--ei sitioVip N` y `--ez volcar true` (guarda las listas leídas en `files/`, que se bajan
con `adb exec-out run-as ar.partidostv cat files/principal.json`).
