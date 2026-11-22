package ar.partidostv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.util.Locale;

/**
 * Dos WebViews apiladas:
 *  - "lector" (invisible): carga los sitios como un navegador común, uno detrás del otro
 *    (primero el principal, después VIPBox), lee cada lista y al final se descarga (about:blank)
 *    para que no le robe CPU a la interfaz.
 *  - "ui": la interfaz propia (assets/ui.html), pensada para el control remoto.
 * Al elegir un partido se abre su página en el navegador de la tele (Intent ACTION_VIEW).
 *
 * Ojo: todas las WebViews de una app comparten un solo proceso de renderizado, así que
 * mientras un sitio está cargado su JavaScript compite con el de la interfaz.
 */
public class MainActivity extends Activity {

    /**
     * Lista de direcciones editable desde el celular (gist secreto de GitHub). Sin número de
     * revisión en el link, así siempre trae la última edición (GitHub la cachea ~5 min).
     */
    static final String URL_CONFIG =
            "https://gist.githubusercontent.com/NicoDiddi11/9ad5c6ecb5d0ff48eb9860c2e73f197b/raw/partidos-tv.txt";

    /** Direcciones de fábrica: se usan solo si nunca se pudo leer el gist. */
    static final String[][] SITIOS_FABRICA = {
            {"MadPlay77", "https://jack11eo.mpaver6fuiwild.my/es"},
            {"FCTV33", "https://www.fctv33go.beauty/es"},
    };
    static final String[][] VIP_FABRICA = {
            {"VIPBox", "https://www.vipboxtv.st/live-now-stream"},
            {"VIPRow", "https://www.viprow.lc/sports-live-now"},
    };

    /**
     * Grupo «Argentina»: agendas de sitios argentinos, en orden de importancia (manda el primero al unir).
     * El NOMBRE dice cómo se lee cada uno (no cambiarlo); la dirección sí se puede cambiar desde el gist.
     * AngulismoTV2 es la misma página en otro dominio: se usa solo si la primera no responde.
     */
    static final String[][] ARG_FABRICA = {
            {"TVLibre", "https://tvlibre.st/agenda/"},
            {"DeportesTVHD", "https://deportestvhd.me/"},
            {"AngulismoTV", "https://angulismotv.pages.dev/transmision/"},
            {"AngulismoTV2", "https://99fcafcf.angulismotv.pages.dev/transmision"},
            {"EsporteVIP", "https://esportevip.elementfx.com/"},
    };
    /** De donde AngulismoTV (sus dos dominios) saca los partidos. Se cambia con «argentina AngulismoDatos = …». */
    static final String DATOS_ANGULISMO =
            "https://raw.githubusercontent.com/tadeoallende/repos-web/refs/heads/main/datos.json";
    String[][] sitiosArg = ARG_FABRICA;
    /** Qué grupo se está mirando: false = Internacional (MadPlay77 + VIPBox), true = Argentina. */
    boolean modoArgentina;

    /** Fuente principal (resultados, minuto, escudos), en orden de preferencia. El usuario elige con «Fuente». */
    String[][] sitios = SITIOS_FABRICA;
    /** Fuente complementaria (más deportes, hora exacta, segundo link). Si la primera falla, se usa la siguiente. */
    String[][] sitiosVip = VIP_FABRICA;

    /** Host al que redirigió (por HTTP) el sitio de esta fase; se acepta solo si ahí aparecen partidos. */
    String hostCandidato;
    String urlAntesDeRedirigir;

    static final int FASE_PRINCIPAL = 0, FASE_VIP = 1;

    static final long ESPERA_PRIMERA_LECTURA_MS = 1200;
    static final long ENTRE_LECTURAS_MS = 800;
    static final int MAX_LECTURAS = 40;          // ~32 s esperando la lista principal
    static final int MAX_LECTURAS_VIP = 25;      // ~20 s para la complementaria
    static final int LECTURAS_ESTABLES = 2;      // cantidad igual 2 veces seguidas = terminó de cargar
    static final long AUTO_ACTUALIZAR_MS = 120_000;

    /** Recursos que el lector nunca necesita: se le devuelve una respuesta vacía. */
    static final String[] BLOQUEAR_EXT = {".woff2", ".woff", ".ttf", ".otf", ".mp4", ".webm", ".m3u8", ".mp3", ".gif"};
    static final String[] BLOQUEAR_HOST = {"googletagmanager.com", "google-analytics.com", "doubleclick.net",
            "googlesyndication.com", "adservice.google.com", "facebook.net", "hotjar.com", "clarity.ms"};

    /**
     * Fuente principal. Junta los links a páginas de partido con su texto, los escudos
     * (atributo origin-src, que el sitio usa para cargarlos después) y el encabezado de liga con su bandera.
     */
    static final String JS_EXTRAER =
            "(function(){try{" +
            "function img(i){if(!i)return '';var s=i.getAttribute('origin-src')||i.getAttribute('data-src')||i.getAttribute('src')||'';" +
            " return /^https:/.test(s)&&!/icon_|_def[@.]/.test(s)?s:'';}" +
            // ¿Se ve el elemento? (él y sus padres hasta la fila, sin display:none / visibility:hidden / opacity 0)
            "function visible(e,tope){if(!e)return false;for(var n=e;n&&n!==tope.parentElement;n=n.parentElement){" +
            " var cs=getComputedStyle(n);if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')return false;}return true;}" +
            // Link de partido en los dos sitios: /<idioma>/<deporte>/<liga>[-match]-<número>/<equipos>.html
            "var RE=/^\\/(?:[a-z]{2}\\/)?[a-z-]+\\/(?!league-)[^\\/]+-\\d+\\/[^\\/]+\\.html$/;" +
            "var as=[].filter.call(document.querySelectorAll('a[href$=\".html\"]'),function(a){" +
            " if(a.host!==location.host||!RE.test(a.pathname))return false;a.setAttribute('data-ptv','1');return true;});" +
            "function cab(a){var el=a;for(var d=0;d<6&&el;d++){var s=el.previousElementSibling,k=0;" +
            " while(s&&k<300){var esPartido=s.hasAttribute('data-ptv')||s.querySelector('a[data-ptv]');" +
            "  if(!esPartido){var t=(s.innerText||'').trim();if(t&&t.length<120)return s;}" +
            "  s=s.previousElementSibling;k++;}" +
            " el=el.parentElement;}return null;}" +
            "var out=[],vistos={},cabs=new Map();" +
            "for(var i=0;i<as.length;i++){var a=as[i],h=a.href;if(!h||vistos[h])continue;vistos[h]=1;" +
            " var c=cab(a),info=c?cabs.get(c):null;" +
            " if(c&&!info){var fl='',ims=c.querySelectorAll('img');for(var j=0;j<ims.length&&!fl;j++)fl=img(ims[j]);" +
            "  info={t:(c.innerText||'').trim(),f:fl};cabs.set(c,info);}" +
            " var lg=a.querySelectorAll('img.r-logo');" +
            // En vivo según el propio sitio: el ícono de transmisión (…live_stream…) se MUESTRA solo en los
            // partidos que se están jugando; en el resto está en el HTML pero oculto.
            " var ic=a.querySelector('img[src*=\"live_stream\"]');" +
            " out.push({href:h,text:(a.innerText||'').trim(),liga:info?info.t:'',bandera:info?info.f:''," +
            "  logo1:img(lg[0]),logo2:img(lg[1]),enVivo:visible(ic,a)});}" +
            "var todos=document.querySelectorAll('a[href]'),ej=[];for(var q=0;q<todos.length&&ej.length<4;q+=Math.max(1,todos.length>>2))ej.push(todos[q].getAttribute('href'));" +
            "return JSON.stringify({ok:true,items:out,url:location.href,links:todos.length,ej:ej," +
            " largo:document.body?document.body.innerText.length:0,vis:document.visibilityState});" +
            "}catch(e){return JSON.stringify({ok:false,error:String(e),items:[]});}})()";

    /**
     * VIPBox / VIPRow: cada evento es un link /<deporte o liga>/<nombre>-stream-live (o -online-stream)
     * con un <time content="2026-11-21T17:30+00:00"> (hora exacta en UTC) y un ícono con el país.
     */
    static final String JS_VIP =
            "(function(){try{" +
            "var RE=/^\\/[^\\/]+\\/[^\\/]+-(?:stream-live|online-stream)$/;" +
            "var out=[],vistos={};" +
            "[].forEach.call(document.querySelectorAll('a[href]'),function(a){" +
            " if(a.host!==location.host||!RE.test(a.pathname)||vistos[a.href])return;vistos[a.href]=1;" +
            " var tm=a.querySelector('time'),ic=a.querySelector('.event-icon,.vip-row-icon,i,span[class*=icon]');" +
            " var pais=ic?[].filter.call(ic.classList,function(c){return !/^(align-|me-|event-icon$|vip-row-icon$)/.test(c);}).join(' '):'';" +
            " var tit=(a.getAttribute('title')||a.innerText||'').trim();" +
            " out.push({href:a.href,titulo:tit,t:tm?tm.getAttribute('content'):'',pais:pais,seccion:a.pathname.split('/')[1]});});" +
            "return JSON.stringify({ok:true,items:out,url:location.href});" +
            "}catch(e){return JSON.stringify({ok:false,error:String(e),items:[]});}})()";

    final Handler handler = new Handler(Looper.getMainLooper());
    WebView ui, lector;
    FrameLayout raiz;
    SharedPreferences prefs;
    int sitio;              // índice en sitios (lo elige el usuario)
    int sitioVip;           // índice en sitiosVip (automático)
    int fase;
    int turno;              // invalida lecturas de una carga anterior
    int lecturas, ultimoTotal, estables;
    long inicioCarga;
    boolean viendoSitio;    // modo diagnóstico: se muestra el sitio en vez de la interfaz
    boolean uiLista;
    boolean huboDatos;      // si ya se mostró alguna lista principal en esta sesión
    boolean probeOtroSitio, probeOtroVip;
    boolean volcar;         // pruebas: guarda cada lista leída en files/ (se baja con adb run-as)

    void volcarArchivo(String nombre, String contenido) {
        if (!volcar) return;
        try (java.io.FileOutputStream f = openFileOutput(nombre, MODE_PRIVATE)) {
            f.write(contenido.getBytes("UTF-8"));
        } catch (Exception e) {
            Log.w("PartidosTV", "no se pudo volcar " + nombre, e);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("partidos", MODE_PRIVATE);
        prefs.edit().remove("nowfutx").apply();   // v20: NowFutx ya no se usa (borra su lista guardada)
        // La versión 1 guardaba el índice con FCTV33 primero: se resetea a la nueva preferencia.
        if (prefs.getInt("version", 1) < 2) prefs.edit().putInt("version", 2).putInt("sitio", 0).apply();
        // Última lista de direcciones que se leyó del gist (si nunca se pudo, quedan las de fábrica).
        String[][][] guardada = interpretarConfig(prefs.getString("config", ""));
        if (guardada != null) aplicarConfig(guardada);
        else aplicarConfig(new String[][][]{SITIOS_FABRICA, VIP_FABRICA, ARG_FABRICA});
        modoArgentina = "argentina".equals(prefs.getString("grupo", ""));
        sitio = Math.min(prefs.getInt("sitio", 0), sitios.length - 1);
        sitioVip = Math.min(prefs.getInt("sitioVip", 0), sitiosVip.length - 1);
        // Para pruebas por adb: am start ... --ei sitio 1 --ei sitioVip 1 (fuerza una fuente sin guardarla)
        Intent arranque = getIntent();
        if (arranque != null) {
            sitio = Math.max(0, Math.min(arranque.getIntExtra("sitio", sitio), sitios.length - 1));
            sitioVip = Math.max(0, Math.min(arranque.getIntExtra("sitioVip", sitioVip), sitiosVip.length - 1));
            volcar = arranque.getBooleanExtra("volcar", false);
            // Pruebas por adb: --es grupo argentina (abre en ese grupo, como si lo hubieras elegido)
            String g = arranque.getStringExtra("grupo");
            if (g != null) {
                modoArgentina = "argentina".equals(g);
                prefs.edit().putString("grupo", modoArgentina ? "argentina" : "internacional").apply();
            }
            String[][][] prueba = interpretarConfig(arranque.getStringExtra("configPrueba"));
            if (prueba != null) {
                configDePrueba = true;
                aplicarConfig(prueba);
                sitio = 0;
                sitioVip = 0;
            }
        }

        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Color.parseColor("#151821"));

        // --- Lector (los sitios reales): invisible y sin imágenes ---
        lector = new WebView(this);
        WebSettings ls = lector.getSettings();
        ls.setJavaScriptEnabled(true);
        ls.setDomStorageEnabled(true);
        ls.setBlockNetworkImage(true);                // las URLs de escudos se leen igual del HTML
        ls.setMediaPlaybackRequiresUserGesture(true);
        ls.setSupportMultipleWindows(false);          // nada de ventanas emergentes
        ls.setJavaScriptCanOpenWindowsAutomatically(false);
        ls.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= 26) lector.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true);
        lector.setFocusable(false);
        lector.setVisibility(View.INVISIBLE);
        lector.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Solo se permite navegar entre los sitios conocidos: corta redirecciones de propaganda.
                Uri u = request.getUrl();
                if (permitido(u)) return false;
                // Excepción: el sitio se mudó y el servidor redirige (301/302) a su dominio nuevo.
                // Se deja pasar a prueba; se recuerda solo si ahí aparecen partidos (ver recordarMudanza).
                // Las redirecciones de propaganda son por JavaScript, no de servidor: esas siguen bloqueadas.
                if (Build.VERSION.SDK_INT >= 24 && request.isRedirect() && request.isForMainFrame()
                        && "https".equals(u.getScheme()) && !innecesario(u) && hostCandidato == null) {
                    hostCandidato = u.getHost();
                    Log.i("PartidosTV", "redirección de servidor a " + hostCandidato + ", se prueba");
                    return false;
                }
                return true;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !permitido(Uri.parse(url));
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (viendoSitio) return null;
                if (innecesario(request.getUrl())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
        });
        raiz.addView(lector, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // --- Interfaz propia ---
        ui = new WebView(this);
        WebSettings us = ui.getSettings();
        us.setJavaScriptEnabled(true);
        us.setDomStorageEnabled(true);   // favoritos, filtros y últimas listas quedan guardados
        ui.setBackgroundColor(Color.parseColor("#151821"));
        ui.addJavascriptInterface(new Puente(), "Android");
        ui.setWebViewClient(new WebViewClient() {
            // La interfaz tiene el puente «Android» (abrir links): nunca puede navegar fuera de su propio
            // archivo, así ninguna página de internet llega a tenerlo a mano.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !request.getUrl().toString().startsWith("file:///android_asset/");
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                uiLista = true;
                ui.evaluateJavascript("app.setGrupo('" + (modoArgentina ? "argentina" : "internacional") + "')", null);
                enviarSitios();
                enviarCanales();        // los canales 24 h guardados se ven desde el primer momento
                if (recargarAlCargarUi) {
                    recargarAlCargarUi = false;
                    Log.i("PartidosTV", "la lectura del arranque se cortó con una pausa: se repite");
                    cargarSitio();
                }
                if (argentinaPendiente != null) {
                    ui.evaluateJavascript("app.setArgentina(" + JSONObject.quote(argentinaPendiente) + ")", null);
                    argentinaPendiente = null;
                    return;
                }
                estado(modoArgentina ? "Cargando Argentina…" : "Cargando " + sitios[sitio][0] + "…");
            }
        });
        raiz.addView(ui, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(raiz);
        ui.loadUrl("file:///android_asset/ui.html");
        ui.requestFocus();
        cargarSitio();          // arranca ya con lo guardado…
        leerConfigRemota();     // …y en paralelo busca si cambiaron las direcciones
        // Diagnóstico solo por adb: muestra el sitio principal tal como lo ve el lector (sin imágenes ni video).
        if (getIntent() != null && getIntent().getBooleanExtra("diagnosticoSitio", false)) {
            handler.postDelayed(() -> verSitio(true), 500);
        }
    }

    // ------------------------------------------------------------------ lista de direcciones (gist)

    /**
     * Formato (una por línea, # = comentario):  principal MadPlay77 = https://…   /   vip VIPBox = https://…
     *   /   argentina TVLibre = https://…
     * Devuelve {principales, vips, argentinas} o null si el texto no sirve (así un error al editar no rompe
     * nada). Si no trae ninguna «argentina», ese grupo usa las de fábrica.
     */
    static String[][][] interpretarConfig(String txt) {
        if (txt == null || txt.isEmpty()) return null;
        java.util.List<String[]> pr = new java.util.ArrayList<>(), vp = new java.util.ArrayList<>(),
                ar = new java.util.ArrayList<>();
        java.util.regex.Pattern linea = java.util.regex.Pattern.compile(
                "^\\s*(principal|vip|argentina)\\s+(\\S+)\\s*=\\s*(https://[^\\s/]+\\.[^\\s/]+\\S*)\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String l : txt.split("\\r?\\n")) {
            if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
            java.util.regex.Matcher m = linea.matcher(l);
            if (!m.matches()) continue;
            String[] s = {m.group(2), m.group(3)};
            String tipo = m.group(1).toLowerCase(Locale.ROOT);
            if (tipo.equals("principal")) pr.add(s); else if (tipo.equals("vip")) vp.add(s); else ar.add(s);
            if (pr.size() + vp.size() + ar.size() > 30) break;
        }
        if (pr.isEmpty()) return null;   // sin fuente principal no hay app
        return new String[][][]{pr.toArray(new String[0][]), vp.toArray(new String[0][]),
                ar.isEmpty() ? ARG_FABRICA : ar.toArray(new String[0][])};
    }

    /** Aplica una lista, reemplazando cada dirección por la que se aprendió de una redirección, si hay. */
    /** La lista tal como vino (gist, guardada o de fábrica), antes de aplicar mudanzas aprendidas. */
    String[][][] configBase;
    /** Pruebas por adb: --es configPrueba "principal X = https://…" usa esa lista sin guardarla ni leer el gist. */
    boolean configDePrueba;

    void aplicarConfig(String[][][] cfg) {
        configBase = cfg;
        sitios = conAprendidas(cfg[0]);
        sitiosVip = conAprendidas(cfg[1]);
        sitiosArg = conAprendidas(cfg.length > 2 ? cfg[2] : ARG_FABRICA);
        sitio = Math.max(0, Math.min(sitio, sitios.length - 1));
        sitioVip = Math.max(0, Math.min(sitioVip, Math.max(0, sitiosVip.length - 1)));
    }

    String[][] conAprendidas(String[][] lista) {
        String[][] r = new String[lista.length][];
        for (int i = 0; i < lista.length; i++) {
            r[i] = new String[]{lista[i][0], prefs.getString("redir:" + lista[i][1], lista[i][1])};
        }
        return r;
    }

    void leerConfigRemota() {
        if (configDePrueba) return;
        new Thread(() -> {
            String txt = null;
            java.net.HttpURLConnection c = null;
            try {
                c = (java.net.HttpURLConnection) new java.net.URL(URL_CONFIG).openConnection();
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setUseCaches(false);
                if (c.getResponseCode() == 200) {
                    java.io.InputStream in = c.getInputStream();
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0 && out.size() < 32768) out.write(buf, 0, n);
                    txt = out.toString("UTF-8");
                }
            } catch (Exception e) {
                Log.i("PartidosTV", "no se pudo leer la lista de direcciones: " + e);
            } finally {
                if (c != null) c.disconnect();
            }
            // trim(): Android, al guardar en preferencias un texto que termina en salto de línea,
            // le suma espacios al final; sin esto, cada arranque creía que la lista había cambiado.
            final String leido = txt == null ? null : txt.trim();
            handler.post(() -> {
                String[][][] cfg = interpretarConfig(leido);
                if (cfg == null) {
                    if (leido != null) Log.w("PartidosTV", "lista de direcciones inválida: se sigue con la anterior");
                    return;
                }
                if (leido.equals(prefs.getString("config", "").trim())) {
                    Log.i("PartidosTV", "lista de direcciones sin cambios");
                    return;
                }
                prefs.edit().putString("config", leido).apply();
                String antes = sitios[sitio][1];
                aplicarConfig(cfg);
                enviarSitios();
                Log.i("PartidosTV", "lista de direcciones actualizada: " + sitios.length + " principales, " + sitiosVip.length + " vip");
                // Si cambió la dirección de lo que se está mostrando y todavía no hay datos, se recarga ya.
                // Solo si la app está a la vista: mientras mirás un partido no se carga nada de fondo.
                if (!pausada && !huboDatos && !antes.equals(sitios[sitio][1])) cargarSitio();
            });
        }, "config").start();
    }

    /** Cualquiera de los sitios configurados (FCTV33 a veces redirige a MadPlay77). */
    boolean permitido(Uri destino) {
        if (destino == null || destino.getHost() == null) return false;
        String h = destino.getHost().toLowerCase(Locale.ROOT);
        // Dominio al que se está probando la mudanza (con o sin "www.").
        if (hostCandidato != null && dominioBase(h).equals(dominioBase(hostCandidato.toLowerCase(Locale.ROOT)))) return true;
        for (String[][] grupo : new String[][][]{sitios, sitiosVip, sitiosArg}) {
            for (String[] s : grupo) {
                if (s[0].endsWith("Datos")) continue;    // de ahí solo se leen datos, nunca se abre nada
                String base = dominioBase(Uri.parse(s[1]).getHost());
                if (h.equals(base) || h.endsWith("." + base)) return true;
            }
        }
        return false;
    }

    static boolean innecesario(Uri u) {
        if (u == null) return false;
        String path = u.getPath() == null ? "" : u.getPath().toLowerCase(Locale.ROOT);
        for (String e : BLOQUEAR_EXT) if (path.endsWith(e)) return true;
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        for (String b : BLOQUEAR_HOST) if (h.equals(b) || h.endsWith("." + b)) return true;
        return false;
    }

    /**
     * "jack11eo.mpaver6fuiwild.my" -> "mpaver6fuiwild.my". En los alojamientos gratuitos (pages.dev…) cada
     * sitio es un subdominio: "99fcafcf.angulismotv.pages.dev" -> "angulismotv.pages.dev", así permitir
     * AngulismoTV no permite cualquier otra página de pages.dev.
     */
    static final String[] ALOJAMIENTOS = {"pages.dev", "workers.dev", "github.io", "netlify.app", "vercel.app", "web.app",
            "elementfx.com"};

    static String dominioBase(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        String dos = p[p.length - 2] + "." + p[p.length - 1];
        for (String a : ALOJAMIENTOS) {
            if (a.equals(dos)) return p[p.length - 3] + "." + dos;
        }
        return dos;
    }

    // ------------------------------------------------------------------ lectura de los sitios

    /** Arranca una actualización completa: primero la fuente principal, después VIPBox. */
    void cargarSitio() {
        probeOtroVip = false;
        if (modoArgentina) {
            // Grupo Argentina: casi todo es texto (HTML/JSON); el lector solo se usa para EsporteVIP. Se
            // descarga por si estaba a mitad de leer MadPlay77 (cambiaste de grupo).
            handler.removeCallbacksAndMessages(null);
            turno++;
            liberarLector();
            leerArgentina(turno);
            return;
        }
        empezarFase(FASE_PRINCIPAL);
    }

    void empezarFase(int f) {
        if (f == FASE_VIP && sitiosVip.length == 0) {   // la lista no trae fuente complementaria
            terminar(turno);
            return;
        }
        fase = f;
        turno++;
        hostCandidato = null;
        urlAntesDeRedirigir = f == FASE_PRINCIPAL ? sitios[sitio][1] : sitiosVip[sitioVip][1];
        lecturas = 0;
        ultimoTotal = -1;
        estables = 0;
        inicioCarga = SystemClock.elapsedRealtime();
        handler.removeCallbacksAndMessages(null);
        lector.onResume();
        lector.loadUrl(f == FASE_PRINCIPAL ? sitios[sitio][1] : sitiosVip[sitioVip][1]);
        final int t = turno;
        handler.postDelayed(() -> leer(t), ESPERA_PRIMERA_LECTURA_MS);
    }

    /** Descarga el sitio para que deje de consumir CPU y memoria hasta la próxima actualización. */
    void liberarLector() {
        // Mientras lee EsporteVIP (grupo Argentina) el lector está ocupado: lo libera ese lector al terminar.
        if (viendoSitio || (leyendoEsporte && esporteTurno == turno)) return;
        lector.stopLoading();
        lector.loadUrl("about:blank");
        lector.onPause();
    }

    void leer(final int t) {
        if (t != turno) return;
        lecturas++;
        lector.evaluateJavascript(fase == FASE_PRINCIPAL ? JS_EXTRAER : JS_VIP, resultado -> procesar(t, resultado));
    }

    void procesar(int t, String resultado) {
        if (t != turno) return;
        int total = 0;
        String error = null;
        JSONObject o = null;
        try {
            Object v = new JSONTokener(resultado).nextValue();   // viene como string JSON
            o = new JSONObject((String) v);
            JSONArray items = o.optJSONArray("items");
            total = items == null ? 0 : items.length();
            if (!o.optBoolean("ok", false)) error = o.optString("error");
        } catch (Exception e) {
            error = "respuesta ilegible";
        }
        if (fase == FASE_PRINCIPAL) procesarPrincipal(t, resultado, total, error, o);
        else procesarVip(t, resultado, total, o);
    }

    void procesarPrincipal(int t, String resultado, int total, String error, JSONObject o) {
        if (total == 0 && o != null && lecturas % 4 == 0) {
            Log.i("PartidosTV", "sin partidos aún: " + o.optString("url") + " links=" + o.optInt("links")
                    + " largo=" + o.optInt("largo") + " ej=" + o.optJSONArray("ej"));
        }
        if (total > 0) {
            if (total != ultimoTotal) {
                // Se manda tal cual (string JSON) a la interfaz; ella lo interpreta.
                ui.evaluateJavascript("app.setData(" + resultado + ")", null);
                if (!huboDatos || ultimoTotal <= 0) estado("");
                huboDatos = true;
            }
            estables = (total == ultimoTotal) ? estables + 1 : 0;
            ultimoTotal = total;
        }

        if (total > 0 && estables >= LECTURAS_ESTABLES) {
            Log.i("PartidosTV", sitios[sitio][0] + ": " + total + " partidos en " + segundos() + " s");
            volcarArchivo("principal.json", resultado);
            recordarMudanza(o);
            liberarLector();          // la principal ya está: el sitio se descarga mientras se baja VIPBox/VIPRow
            leerVipDirecto(t);
            return;
        }
        if (lecturas >= MAX_LECTURAS) {
            if (total == 0 && !huboDatos && !probeOtroSitio && sitios.length > 1) {
                // El sitio elegido no respondió: se prueba el otro una vez.
                probeOtroSitio = true;
                sitio = (sitio + 1) % sitios.length;
                enviarSitios();
                estado("No respondió, probando " + sitios[sitio][0] + "…");
                empezarFase(FASE_PRINCIPAL);
                return;
            }
            if (total == 0) {
                // "!" = falla: la interfaz arma el texto según tenga o no una lista anterior para mostrar.
                Log.i("PartidosTV", sitios[sitio][0] + " sin partidos" + (error != null ? " (" + error + ")" : ""));
                estado("!" + sitios[sitio][0]);
            }
            liberarLector();
            leerVipDirecto(t);       // aunque falle la principal, VIPBox/VIPRow pueden aportar su lista
            return;
        }
        if (total == 0 && !huboDatos) estado("Cargando " + sitios[sitio][0] + "… (" + lecturas + ")");
        handler.postDelayed(() -> leer(t), ENTRE_LECTURAS_MS);
    }

    void procesarVip(int t, String resultado, int total, JSONObject o) {
        if (total > 0) {
            if (total != ultimoTotal) ui.evaluateJavascript("app.setVip(" + resultado + ")", null);
            estables = (total == ultimoTotal) ? estables + 1 : 0;
            ultimoTotal = total;
        }
        if (total > 0 && estables >= LECTURAS_ESTABLES) {
            Log.i("PartidosTV", sitiosVip[sitioVip][0] + ": " + total + " eventos en " + segundos() + " s");
            volcarArchivo("vip.json", resultado);
            recordarMudanza(o);
            terminar(t);
            return;
        }
        if (lecturas >= MAX_LECTURAS_VIP) {
            if (total == 0 && !probeOtroVip && sitiosVip.length > 1) {
                probeOtroVip = true;
                sitioVip = (sitioVip + 1) % sitiosVip.length;   // VIPBox caído: se prueba VIPRow
                prefs.edit().putInt("sitioVip", sitioVip).apply();
                Log.i("PartidosTV", "complementaria sin datos, probando " + sitiosVip[sitioVip][0]);
                empezarFase(FASE_VIP);
                return;
            }
            terminar(t);
            return;
        }
        handler.postDelayed(() -> leer(t), ENTRE_LECTURAS_MS);
    }

    /**
     * Si esta fase llegó a sus partidos después de una redirección de servidor a otro dominio,
     * el sitio se mudó: se guarda la dirección nueva para usarla directo la próxima vez.
     */
    void recordarMudanza(JSONObject o) {
        if (hostCandidato == null || o == null) return;
        Uri fin = Uri.parse(o.optString("url"));
        // Si terminó en el dominio de siempre (rebote: vipboxtv.st → .sk → .st), no hubo mudanza.
        if (fin.getHost() == null || !dominioBase(fin.getHost().toLowerCase(Locale.ROOT))
                .equals(dominioBase(hostCandidato.toLowerCase(Locale.ROOT)))) return;
        String nueva = "https://" + fin.getHost() + (fin.getPath() == null ? "" : fin.getPath());
        // La clave es la dirección ORIGINAL de la lista: si después editás el gist, manda el gist.
        String original = null;
        String[][] base = configBase[fase == FASE_PRINCIPAL ? 0 : 1];
        int idx = fase == FASE_PRINCIPAL ? sitio : sitioVip;
        if (idx < base.length) original = base[idx][1];
        if (original == null) return;
        prefs.edit().putString("redir:" + original, nueva).apply();
        if (fase == FASE_PRINCIPAL) sitios[sitio][1] = nueva; else sitiosVip[sitioVip][1] = nueva;
        Log.i("PartidosTV", "el sitio se mudó: " + original + " → " + nueva + " (guardado)");
        hostCandidato = null;
    }

    // ------------------------------------------------------------------ VIPBox + VIPRow, sin navegador

    /** Cada evento en el HTML: <a … href="/<sección>/<nombre>-stream-live|-online-stream" title="…" data-bs-target="#id">…</a> */
    static final java.util.regex.Pattern P_EVENTO = java.util.regex.Pattern.compile(
            "<a\\s([^>]*?href=\"(/[^\"/]+/[^\"/]+-(?:stream-live|online-stream))\"[^>]*)>(.{0,700}?)</a>",
            java.util.regex.Pattern.DOTALL);
    static final java.util.regex.Pattern P_TITULO = java.util.regex.Pattern.compile("title=\"([^\"]*)\"");
    static final java.util.regex.Pattern P_ID = java.util.regex.Pattern.compile("data-bs-target=\"#(\\d+)\"");
    static final java.util.regex.Pattern P_HORA = java.util.regex.Pattern.compile("<time[^>]*content=\"([^\"]+)\"");
    static final java.util.regex.Pattern P_ICONO = java.util.regex.Pattern.compile(
            "class=\"[^\"]*(?:event-icon|vip-row-icon)\\s+([^\"]*?)\\s*\"");

    static String sinEntidades(String s) {
        return s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&#039;", "'");
    }

    /** Baja una página como texto, como un navegador común (las listas vienen en el HTML, sin JavaScript). */
    static String bajar(String url) throws java.io.IOException {
        return bajarConDestino(url)[0];
    }

    /** Igual que bajar(), pero devuelve también la dirección final (después de redirecciones). */
    static String[] bajarConDestino(String url) throws java.io.IOException {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36");
            c.setRequestProperty("Accept-Language", "es-AR,es;q=0.9,en;q=0.8");
            if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 4_000_000) out.write(buf, 0, n);
            return new String[]{out.toString("UTF-8"), c.getURL().toString()};
        } finally {
            c.disconnect();
        }
    }

    /** Eventos de una página de VIPBox/VIPRow, en el mismo formato que usaba la lectura con navegador. */
    static java.util.List<JSONObject> eventosDe(String base, String html, String fuente) throws Exception {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        java.util.regex.Matcher m = P_EVENTO.matcher(html);
        while (m.find()) {
            String attrs = m.group(1), path = m.group(2), cuerpo = m.group(3);
            java.util.regex.Matcher mt = P_TITULO.matcher(attrs), mi = P_ID.matcher(attrs),
                    mh = P_HORA.matcher(cuerpo), mc = P_ICONO.matcher(cuerpo);
            JSONObject e = new JSONObject();
            e.put("href", base + path);
            e.put("titulo", mt.find() ? sinEntidades(mt.group(1)).trim() : "");
            e.put("t", mh.find() ? mh.group(1) : "");
            e.put("pais", mc.find() ? mc.group(1).trim() : "");
            e.put("seccion", path.split("/")[1]);
            e.put("id", mi.find() ? mi.group(1) : "");
            e.put("fuente", fuente);
            r.add(e);
        }
        return r;
    }

    /**
     * Lee VIPBox y VIPRow A LA VEZ (son el mismo sitio pero no siempre tienen los mismos eventos) y las
     * junta sin repetir: los dos usan el mismo número de evento (data-bs-target). Sin navegador interno:
     * es texto plano, mucho más liviano para la tele. Si no anda, se vuelve a la lectura con navegador.
     */
    void leerVipDirecto(final int t) {
        if (sitiosVip.length == 0) { terminar(t); return; }
        final String[][] lista = sitiosVip;
        final long inicio = SystemClock.elapsedRealtime();
        new Thread(() -> {
            java.util.List<Thread> hilos = new java.util.ArrayList<>();
            final java.util.List<java.util.List<JSONObject>> porSitio = new java.util.ArrayList<>();
            final String[] notas = new String[lista.length];
            for (int i = 0; i < lista.length; i++) {
                porSitio.add(new java.util.ArrayList<>());
                final int k = i;
                Thread h = new Thread(() -> {
                    try {
                        String[] pagina = bajarConDestino(lista[k][1]);
                        // Los links se arman con la dirección FINAL (si el sitio redirigió). Si terminó en un
                        // dominio que no es de la lista, no se usa: esos links no se podrían abrir.
                        Uri u = Uri.parse(pagina[1]);
                        if (!permitido(u)) throw new java.io.IOException("redirigió a " + u.getHost());
                        String base = u.getScheme() + "://" + u.getHost();
                        porSitio.set(k, eventosDe(base, pagina[0], lista[k][0]));
                        notas[k] = lista[k][0] + " " + porSitio.get(k).size();
                    } catch (Exception e) {
                        notas[k] = lista[k][0] + " falló (" + e.getMessage() + ")";
                    }
                });
                hilos.add(h);
                h.start();
            }
            for (Thread h : hilos) { try { h.join(15000); } catch (InterruptedException ignored) { } }

            // Unión sin repetir (por número de evento; si no hay, por el nombre del link). Manda el orden de la lista.
            JSONArray items = new JSONArray();
            java.util.Map<String, JSONObject> vistos = new java.util.HashMap<>();
            for (java.util.List<JSONObject> l : porSitio) {
                for (JSONObject e : l) {
                    String id = e.optString("id");
                    String slug = e.optString("href").replaceAll("^.*/", "").replaceAll("-(stream-live|online-stream)$", "");
                    String porId = id.isEmpty() ? null : "i:" + id, porNombre = "s:" + slug;
                    JSONObject ya = porId != null && vistos.containsKey(porId) ? vistos.get(porId) : vistos.get(porNombre);
                    if (ya != null) {
                        // Mismo evento en el otro sitio: se guarda lo que ese sitio sabe (VIPRow suele decir
                        // la liga y el país, p. ej. "bolivia-lfpb", donde VIPBox solo dice "football").
                        try {
                            if (!ya.has("seccion2")) {
                                ya.put("seccion2", e.optString("seccion"));
                                ya.put("pais2", e.optString("pais"));
                            }
                        } catch (Exception ignored) { }
                        continue;
                    }
                    if (porId != null) vistos.put(porId, e);
                    vistos.put(porNombre, e);
                    items.put(e);
                }
            }
            String nota = android.text.TextUtils.join(" · ", notas);
            long ms = SystemClock.elapsedRealtime() - inicio;
            String json;
            try {
                json = new JSONObject().put("ok", true).put("items", items).put("fuentes", nota).toString();
            } catch (Exception e) {
                json = "{\"ok\":false,\"items\":[]}";
            }
            final String datos = json;
            final int total = items.length();
            handler.post(() -> {
                if (t != turno) return;
                Log.i("PartidosTV", "VIP directo: " + nota + " → " + total + " eventos sin repetir en " + (ms / 100) / 10.0 + " s");
                if (total == 0) {            // ninguna respondió como texto: lectura con navegador, como antes
                    empezarFase(FASE_VIP);
                    return;
                }
                volcarArchivo("vip.json", JSONObject.quote(datos));
                ui.evaluateJavascript("app.setVip(" + JSONObject.quote(datos) + ")", null);
                terminar(t);
                actualizarMapaSiHaceFalta();
            });
        }, "vip").start();
    }

    // ------------------------------------------------------------------ grupo Argentina, sin navegador
    //
    // Cada fuente se baja como texto y se pasa a un formato común:
    //   {fuente, titulo: "Liga: Local vs. Visitante", clase: "AR"|"IT"|"NFL"…, t: instante (ms),
    //    opciones: [{nombre: "ESPN", calidad: "720p", href}], logo1, logo2, logoLiga}
    // La interfaz los une sin repetir (como hace con MadPlay77 + VIPBox).

    static final java.util.TimeZone TZ_AR = java.util.TimeZone.getTimeZone("America/Argentina/Buenos_Aires");
    /** DeportesTVHD escribe las horas en UTC+1 fijo (su página resta «60 − huso» para mostrarlas). */
    static final java.util.TimeZone TZ_DTV = java.util.TimeZone.getTimeZone("GMT+01:00");
    static final String[] MESES = {"enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto",
            "septiembre", "octubre", "noviembre", "diciembre"};

    /** TVLibre: <li class="IT"><a href="#">Serie A: Juventus vs. Venezia <span class="t">08:30</span></a><ul>…opciones…</ul> */
    static final java.util.regex.Pattern P_TVL_EVENTO = java.util.regex.Pattern.compile(
            "<li class=\"([A-Za-z0-9_-]+)\">\\s*<a href=\"#\">(.*?)<span class=\"t\">\\s*(\\d{1,2}:\\d{2})\\s*</span>\\s*</a>\\s*<ul>(.*?)</ul>",
            java.util.regex.Pattern.DOTALL);
    static final java.util.regex.Pattern P_TVL_OPCION = java.util.regex.Pattern.compile(
            "<a href=\"([^\"]+)\"[^>]*>([^<]*)(?:<span>([^<]*)</span>)?", java.util.regex.Pattern.DOTALL);
    /** Encabezado «Agenda - Domingo 22 de Noviembre de 2026». */
    static final java.util.regex.Pattern P_FECHA_AGENDA = java.util.regex.Pattern.compile(
            "(\\d{1,2}) de ([A-Za-z]+) de (\\d{4})");
    static final java.util.regex.Pattern P_HHMM = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})");
    static final java.util.regex.Pattern P_FECHA_HORA = java.util.regex.Pattern.compile(
            "^(\\d{4})-(\\d{2})-(\\d{2})[ T](\\d{2}):(\\d{2})");
    /** Opciones que piden una extensión del navegador de PC: en la tele no andan. */
    static final java.util.regex.Pattern P_EXTENSION = java.util.regex.Pattern.compile(
            "extensi[oó]n|extension", java.util.regex.Pattern.CASE_INSENSITIVE);

    static String sinEtiquetas(String s) {
        return sinEntidades((s == null ? "" : s).replaceAll("<[^>]*>", " ")).replaceAll("\\s+", " ").trim();
    }

    /** Link de una opción: relativo al sitio ("/eventos/?r=…") o del mismo sitio. Otro dominio: no se usa. */
    static String absoluta(String base, String href) {
        if (href == null || href.isEmpty() || href.startsWith("#") || href.startsWith("//")) return null;
        if (href.startsWith("/")) return base + href;
        if (href.startsWith(base + "/")) return href;
        return null;
    }

    static JSONObject opcion(String nombre, String calidad, String href) throws Exception {
        return new JSONObject().put("nombre", nombre).put("calidad", calidad == null ? "" : calidad).put("href", href);
    }

    static java.util.Calendar hoyEn(java.util.TimeZone tz, int masDias) {
        java.util.Calendar c = java.util.Calendar.getInstance(tz);
        c.add(java.util.Calendar.DAY_OF_MONTH, masDias);
        return c;
    }

    /**
     * Horas "HH:MM" de una agenda (en orden) -> instantes. La lista sigue después de medianoche: cuando la
     * hora "vuelve atrás" más de 6 h (de 23:30 a 00:15) se pasa al día siguiente.
     */
    static long[] instantes(java.util.List<String> horas, java.util.Calendar fecha, java.util.TimeZone tz) {
        long[] r = new long[horas.size()];
        int previo = -1, dias = 0;
        for (int i = 0; i < horas.size(); i++) {
            java.util.regex.Matcher m = P_HHMM.matcher(horas.get(i).trim());
            if (!m.find()) continue;
            int h = Integer.parseInt(m.group(1)), mi = Integer.parseInt(m.group(2)), min = h * 60 + mi;
            if (previo >= 0 && min < previo - 360) dias++;
            previo = min;
            java.util.Calendar c = java.util.Calendar.getInstance(tz);
            c.clear();
            c.set(fecha.get(java.util.Calendar.YEAR), fecha.get(java.util.Calendar.MONTH),
                    fecha.get(java.util.Calendar.DAY_OF_MONTH) + dias, h, mi, 0);
            r[i] = c.getTimeInMillis();
        }
        return r;
    }

    /**
     * Agenda sin fecha: es la de hoy en la zona del sitio. Pero pasada la medianoche de esa zona (DeportesTVHD
     * está 4 h adelantado: a las 21 de acá allá ya es mañana) el sitio sigue mostrando la de ayer: si la primera
     * hora quedaría a más de 6 h en el futuro, la lista es de ayer.
     */
    static long[] instantesSinFecha(java.util.List<String> horas, java.util.TimeZone tz) {
        long[] r = instantes(horas, hoyEn(tz, 0), tz);
        long primero = 0;
        for (long x : r) if (x > 0) { primero = x; break; }
        if (primero > System.currentTimeMillis() + 6 * 3600_000L) r = instantes(horas, hoyEn(tz, -1), tz);
        return r;
    }

    static java.util.Calendar fechaAgenda(String html) {
        java.util.regex.Matcher m = P_FECHA_AGENDA.matcher(html);
        if (!m.find()) return null;
        String mes = m.group(2).toLowerCase(Locale.ROOT);
        for (int i = 0; i < MESES.length; i++) {
            if (MESES[i].equals(mes)) {
                java.util.Calendar c = java.util.Calendar.getInstance(TZ_AR);
                c.clear();
                c.set(Integer.parseInt(m.group(3)), i, Integer.parseInt(m.group(1)));
                // Si la fecha no es de ayer, hoy o mañana, el encabezado no es confiable: se ignora.
                long dif = Math.abs(c.getTimeInMillis() - System.currentTimeMillis());
                return dif < 2 * 24 * 3600_000L ? c : null;
            }
        }
        return null;
    }

    /** TVLibre (/agenda/): HTML armado en el servidor, hora de Argentina, con la fecha en el encabezado. */
    static java.util.List<JSONObject> eventosTvLibre(String base, String html, String fuente) throws Exception {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        java.util.List<String> horas = new java.util.ArrayList<>();
        java.util.regex.Matcher m = P_TVL_EVENTO.matcher(html);
        while (m.find()) {
            JSONArray op = new JSONArray();
            java.util.regex.Matcher mo = P_TVL_OPCION.matcher(m.group(4));
            while (mo.find()) {
                String href = absoluta(base, sinEntidades(mo.group(1)));
                String nombre = sinEtiquetas(mo.group(2));
                if (href == null || P_EXTENSION.matcher(nombre).find()) continue;
                op.put(opcion(nombre, sinEtiquetas(mo.group(3)).replaceFirst("(?i)^calidad\\s*", ""), href));
            }
            JSONObject e = new JSONObject();
            e.put("fuente", fuente);
            e.put("clase", m.group(1));
            e.put("titulo", sinEtiquetas(m.group(2)));
            e.put("opciones", op);
            r.add(e);
            horas.add(m.group(3));
        }
        java.util.Calendar fecha = fechaAgenda(html);
        long[] t = fecha != null ? instantes(horas, fecha, TZ_AR) : instantesSinFecha(horas, TZ_AR);
        for (int i = 0; i < r.size(); i++) r.get(i).put("t", t[i]);
        return r;
    }

    /** DeportesTVHD: los eventos están en /eventos.js (const EVENTOS_DATA = [ … ]), horas en UTC+1. */
    static java.util.List<JSONObject> eventosDeportesTvHd(String base, String js, String fuente) throws Exception {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        int i = js.indexOf('['), j = js.lastIndexOf(']');
        if (i < 0 || j <= i) return r;
        JSONArray a = new JSONArray(js.substring(i, j + 1));
        java.util.List<String> horas = new java.util.ArrayList<>();
        for (int k = 0; k < a.length(); k++) {
            JSONObject x = a.optJSONObject(k);       // una coma de más al final deja un null: se saltea
            if (x == null) continue;
            JSONArray op = new JSONArray(), cs = x.optJSONArray("canales");
            for (int c = 0; cs != null && c < cs.length(); c++) {
                JSONObject ca = cs.optJSONObject(c);
                if (ca == null) continue;
                String href = absoluta(base, ca.optString("url")), nombre = ca.optString("nombre").trim();
                if (href == null || P_EXTENSION.matcher(nombre).find()) continue;
                op.put(opcion(nombre, ca.optString("calidad"), href));
            }
            JSONObject e = new JSONObject();
            e.put("fuente", fuente);
            e.put("clase", x.optString("clase"));
            e.put("titulo", x.optString("titulo").trim());
            e.put("opciones", op);
            r.add(e);
            horas.add(x.optString("hora"));
        }
        long[] t = instantesSinFecha(horas, TZ_DTV);
        for (int k = 0; k < r.size(); k++) r.get(k).put("t", t[k]);
        return r;
    }

    /**
     * AngulismoTV: lista en JSON (fecha completa, hora de Argentina, escudos). Cada canal tiene varias
     * «opciones» (señales); el link abre la página del sitio en ese canal, en la primera opción que no
     * pide extensión de PC.
     */
    static java.util.List<JSONObject> eventosAngulismo(String transmision, String json, String fuente) throws Exception {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        Object raiz = new JSONTokener(json).nextValue();
        JSONArray evs = raiz instanceof JSONObject ? ((JSONObject) raiz).optJSONArray("events")
                : raiz instanceof JSONArray ? (JSONArray) raiz : null;
        if (evs == null) return r;
        String sep = transmision.contains("?") ? "&" : "?";
        for (int k = 0; k < evs.length(); k++) {
            JSONObject x = evs.optJSONObject(k);
            if (x == null) continue;
            java.util.regex.Matcher mf = P_FECHA_HORA.matcher(x.optString("fecha"));
            if (!mf.find()) continue;
            java.util.Calendar c = java.util.Calendar.getInstance(TZ_AR);
            c.clear();
            c.set(Integer.parseInt(mf.group(1)), Integer.parseInt(mf.group(2)) - 1, Integer.parseInt(mf.group(3)),
                    Integer.parseInt(mf.group(4)), Integer.parseInt(mf.group(5)), 0);
            String id = x.optString("id");
            JSONArray op = new JSONArray(), cs = x.optJSONArray("canales");
            for (int ci = 0; cs != null && ci < cs.length(); ci++) {
                JSONObject ca = cs.optJSONObject(ci);
                if (ca == null) continue;
                String nombre = ca.optString("name").trim();
                JSONArray os = ca.optJSONArray("options");
                int elegida = -1;
                for (int o = 0; os != null && o < os.length() && elegida < 0; o++) {
                    JSONObject oo = os.optJSONObject(o);
                    if (oo != null && !P_EXTENSION.matcher(oo.optString("name")).find()) elegida = o;
                }
                if (nombre.isEmpty() || elegida < 0) continue;
                String href = transmision + sep + "e=" + Uri.encode(id) + "&c=" + Uri.encode(nombre) + "&o=" + elegida;
                op.put(opcion(nombre, "", href));
            }
            JSONObject e = new JSONObject();
            e.put("fuente", fuente);
            e.put("clase", "");
            e.put("titulo", x.optString("evento").trim());
            e.put("competencia", x.optString("competencia"));
            e.put("t", c.getTimeInMillis());
            e.put("logo1", x.optString("team1Logo"));
            e.put("logo2", x.optString("team2Logo"));
            e.put("logoLiga", x.optString("logoUrl"));
            e.put("opciones", op);
            r.add(e);
        }
        return r;
    }

    /** Lista argentina leída antes de que la interfaz terminara de cargar. */
    String argentinaPendiente;

    /** Primera dirección de la lista que responde (AngulismoTV y su copia en otro dominio). */
    String angulismoVivo;

    /**
     * Lee todas las fuentes argentinas A LA VEZ y manda la lista a la interfaz con el orden de importancia
     * (el del gist / de fábrica). Si una no responde, las otras igual aportan.
     */
    // Lo leído en la actualización en curso, por fuente. Las de texto llegan juntas; EsporteVIP (navegador
    // oculto) puede llegar después: en ese caso la lista se vuelve a mandar con todo.
    final java.util.Map<String, java.util.List<JSONObject>> argPorFuente = new java.util.concurrent.ConcurrentHashMap<>();
    final java.util.Map<String, String> argNotas = new java.util.concurrent.ConcurrentHashMap<>();
    java.util.List<String> argOrden = new java.util.ArrayList<>();
    boolean argTextoListo;
    long argInicio;

    void leerArgentina(final int t) {
        final String[][] lista = sitiosArg;
        argInicio = SystemClock.elapsedRealtime();
        argPorFuente.clear();
        argNotas.clear();
        argTextoListo = false;
        if (uiLista) estado("Cargando Argentina…");
        Log.i("PartidosTV", "Argentina: leyendo " + lista.length + " direcciones (turno " + t + ")");
        // Por nombre: cada sitio se lee a su manera. Las dos direcciones de AngulismoTV son una sola fuente.
        String datosAng0 = DATOS_ANGULISMO;
        final java.util.List<String[]> angulismo = new java.util.ArrayList<>();
        final java.util.List<String[]> otras = new java.util.ArrayList<>();
        String[] esporte = null;
        for (String[] s : lista) {
            String n = s[0].toLowerCase(Locale.ROOT);
            if (n.equals("angulismodatos")) datosAng0 = s[1];
            else if (n.startsWith("angulismo")) angulismo.add(s);
            else if (n.startsWith("esporte")) esporte = s;
            else otras.add(s);
        }
        java.util.List<String> orden = new java.util.ArrayList<>();
        for (String[] s : lista) {
            String n = s[0].toLowerCase(Locale.ROOT);
            if (n.startsWith("angulismo") && !orden.contains("AngulismoTV") && !n.equals("angulismodatos")) orden.add("AngulismoTV");
            else if (!n.startsWith("angulismo")) orden.add(s[0]);
        }
        argOrden = orden;
        if (esporte != null) leerEsporte(t, esporte);
        final String datosAng = datosAng0;
        new Thread(() -> {
            final java.util.Map<String, java.util.List<JSONObject>> porFuente = argPorFuente;
            final java.util.Map<String, String> notas = argNotas;
            java.util.List<Thread> hilos = new java.util.ArrayList<>();
            for (final String[] s : otras) {
                Thread h = new Thread(() -> {
                    String n = s[0].toLowerCase(Locale.ROOT);
                    try {
                        java.util.List<JSONObject> ev;
                        if (n.startsWith("deportestv")) {
                            Uri u = Uri.parse(s[1]);
                            String base = u.getScheme() + "://" + u.getHost();
                            // Su página pide /eventos.js?v=<hora> para no recibir una copia vieja: igual acá.
                            ev = eventosDeportesTvHd(base, bajar(base + "/eventos.js?v=" + System.currentTimeMillis()), s[0]);
                        } else {                      // TVLibre (y cualquier otra con su mismo formato)
                            String[] pagina = bajarConDestino(s[1]);
                            Uri u = Uri.parse(pagina[1]);
                            if (!permitido(u)) throw new java.io.IOException("redirigió a " + u.getHost());
                            ev = eventosTvLibre(u.getScheme() + "://" + u.getHost(), pagina[0], s[0]);
                        }
                        porFuente.put(s[0], ev);
                        notas.put(s[0], s[0] + " " + ev.size());
                    } catch (Exception e) {
                        notas.put(s[0], s[0] + " falló (" + e.getMessage() + ")");
                    }
                });
                hilos.add(h);
                h.start();
            }
            if (!angulismo.isEmpty()) {
                final String urlDatos = datosAng;
                Thread h = new Thread(() -> {
                    try {
                        // La página que se abre: la primera dirección que responde (se recuerda en la sesión).
                        String vivo = angulismoVivo;
                        if (vivo == null) {
                            for (String[] s : angulismo) {
                                try { bajar(s[1]); vivo = s[1]; break; } catch (Exception ignored) { }
                            }
                        }
                        if (vivo == null) throw new java.io.IOException("ninguna dirección responde");
                        angulismoVivo = vivo;
                        java.util.List<JSONObject> ev = eventosAngulismo(vivo, bajar(urlDatos + "?t=" + System.currentTimeMillis()), "AngulismoTV");
                        porFuente.put("AngulismoTV", ev);
                        notas.put("AngulismoTV", "AngulismoTV " + ev.size());
                    } catch (Exception e) {
                        angulismoVivo = null;
                        notas.put("AngulismoTV", "AngulismoTV falló (" + e.getMessage() + ")");
                    }
                });
                hilos.add(h);
                h.start();
            }
            for (Thread h : hilos) { try { h.join(15000); } catch (InterruptedException ignored) { } }
            handler.post(() -> {
                if (t != turno) { Log.i("PartidosTV", "Argentina: lectura vieja descartada"); return; }
                argTextoListo = true;
                publicarArgentina(t, true);
                terminar(t);
            });
        }, "argentina").start();
    }

    /** Junta lo leído hasta ahora (en el orden de importancia) y lo manda a la interfaz. */
    void publicarArgentina(int t, boolean conAviso) {
        JSONArray items = new JSONArray(), ordenJson = new JSONArray();
        java.util.List<String> nota = new java.util.ArrayList<>();
        for (String f : argOrden) {
            ordenJson.put(f);
            java.util.List<JSONObject> l = argPorFuente.get(f);
            if (l != null) for (JSONObject e : l) items.put(e);
            if (argNotas.containsKey(f)) nota.add(argNotas.get(f));
        }
        String json;
        try {
            json = new JSONObject().put("ok", true).put("items", items).put("orden", ordenJson)
                    .put("fuentes", android.text.TextUtils.join(" · ", nota)).toString();
        } catch (Exception e) {
            json = "{\"ok\":false,\"items\":[]}";
        }
        String resumen = android.text.TextUtils.join(" · ", nota);
        int total = items.length();
        long ms = SystemClock.elapsedRealtime() - argInicio;
        volcarArchivo("argentina.json", json);     // pruebas: se guarda aunque la tele esté apagada
        Log.i("PartidosTV", "Argentina: " + resumen + " → " + total + " eventos en " + (ms / 100) / 10.0 + " s");
        if (total > 0) {
            // Al abrir la app, el texto llega a veces antes que la pantalla: se manda cuando esté lista.
            if (uiLista) ui.evaluateJavascript("app.setArgentina(" + JSONObject.quote(json) + ")", null);
            else argentinaPendiente = json;
            estado("");
        } else if (conAviso) {
            estado("!Argentina");
        }
    }

    // ------------------------------------------------------------------ EsporteVIP (LPF Play)

    /**
     * EsporteVIP arma sus listas en el navegador (las pide a su servidor con una firma propia), así que se
     * lee como MadPlay77: el lector carga la página, se despliega «LPF PLAY ARGENTINA» con un clic, y se
     * toman los partidos de ese grupo. Los partidos no tienen página propia: el botón abre la portada.
     */
    static final String JS_ESPORTE =
            "(function(){try{" +
            "var it=[].filter.call(document.querySelectorAll('a.item'),function(a){return /LPF\\s*PLAY/i.test(a.textContent);})[0];" +
            "if(!it)return JSON.stringify({ok:false,error:'sin grupo LPF'});" +
            "var box=it.nextElementSibling;" +
            // El clic se repite cada 4 lecturas mientras siga en «Carregando»: al principio la función que
            // despliega el grupo todavía no existe y el clic no hace nada.
            "window.__ptvN=(window.__ptvN||0)+1;" +
            "var cargando=!box||/Carregando/i.test(box.textContent);" +
            "if(cargando&&window.__ptvN%4===1){it.click();}" +
            "if(cargando)return JSON.stringify({ok:false,esperando:1,txt:box?box.textContent.trim().slice(0,80):''});" +
            "var evs=[].map.call(box.querySelectorAll('li.evento'),function(li){" +
            " var oc=li.getAttribute('onclick')||'',m=oc.match(/iniciar\\('((?:\\\\'|[^'])*)'/);" +
            " var a=li.querySelector('.areae'),sp=a&&a.querySelector('span'),bs=a?a.querySelectorAll(':scope > b'):[];" +
            " return {titulo:m?m[1].replace(/\\\\'/g,\"'\"):'',camp:sp?sp.textContent:'',hora:bs.length?bs[bs.length-1].textContent:''};});" +
            "if(!evs.length&&!/Nenhum evento/i.test(box.textContent))return JSON.stringify({ok:false,esperando:1});" +
            "return JSON.stringify({ok:true,items:evs});" +
            "}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
    static final java.util.regex.Pattern P_HORA_EVIP = java.util.regex.Pattern.compile(
            "(\\d{1,2})/(\\d{1,2})\\D+(\\d{1,2}):(\\d{2})");
    static final long ESPORTE_VIGENCIA_MS = 5 * 60_000L;   // su lista cambia poco: no se recarga en cada vuelta
    java.util.List<JSONObject> esporteUltimo;
    long esporteCuando;
    boolean leyendoEsporte;
    int esporteTurno;

    void leerEsporte(final int t, final String[] s) {
        if (esporteUltimo != null && SystemClock.elapsedRealtime() - esporteCuando < ESPORTE_VIGENCIA_MS) {
            argPorFuente.put(s[0], esporteUltimo);
            argNotas.put(s[0], s[0] + " " + esporteUltimo.size() + " (guardado)");
            return;
        }
        if (viendoSitio) return;
        leyendoEsporte = true;
        esporteTurno = t;
        lector.onResume();
        lector.loadUrl(s[1]);
        handler.postDelayed(() -> leerEsporteVez(t, s, 1), 3000);
    }

    void leerEsporteVez(final int t, final String[] s, final int vez) {
        if (t != turno) { leyendoEsporte = false; return; }
        if (!leyendoEsporte || esporteTurno != t) return;
        if (vez > 25) {                          // ~28 s: el sitio no armó la lista (o no respondió)
            argNotas.put(s[0], s[0] + " falló (no cargó)");
            terminarEsporte(t);
            return;
        }
        // El próximo intento se agenda ya: si el sitio navega o se traba, la respuesta puede no llegar nunca.
        handler.postDelayed(() -> leerEsporteVez(t, s, vez + 1), 1000);
        lector.evaluateJavascript(JS_ESPORTE, r -> {
            if (t != turno || !leyendoEsporte || esporteTurno != t) return;
            JSONObject o = null;
            try { o = new JSONObject((String) new JSONTokener(r).nextValue()); } catch (Exception ignored) { }
            if (vez % 5 == 1) Log.i("PartidosTV", "EsporteVIP intento " + vez + ": " + (r == null ? "null" : r.length() > 120 ? r.substring(0, 120) : r));
            if (o != null && o.optBoolean("ok")) {
                try {
                    Uri u = Uri.parse(s[1]);
                    java.util.List<JSONObject> ev = eventosEsporte(u.getScheme() + "://" + u.getHost() + "/",
                            o.optJSONArray("items"), s[0]);
                    esporteUltimo = ev;
                    esporteCuando = SystemClock.elapsedRealtime();
                    argPorFuente.put(s[0], ev);
                    argNotas.put(s[0], s[0] + " " + ev.size());
                } catch (Exception e) {
                    argNotas.put(s[0], s[0] + " falló (" + e.getMessage() + ")");
                }
                terminarEsporte(t);
            }
        });
    }

    void terminarEsporte(int t) {
        leyendoEsporte = false;
        liberarLector();
        // Si las de texto ya se mostraron, se vuelve a mandar la lista con LPF Play sumado.
        if (argTextoListo) publicarArgentina(t, false);
    }

    /** {titulo:"Douglas Haig x Olimpo", camp:"Federal A > Semifinal - Ida", hora:"22/11 às 16:30"} (hora de Argentina). */
    static java.util.List<JSONObject> eventosEsporte(String portada, JSONArray a, String fuente) throws Exception {
        java.util.List<JSONObject> r = new java.util.ArrayList<>();
        java.util.Calendar hoy = java.util.Calendar.getInstance(TZ_AR);
        for (int k = 0; a != null && k < a.length(); k++) {
            JSONObject x = a.optJSONObject(k);
            if (x == null) continue;
            String tit = x.optString("titulo").trim().replaceAll("\\s+x\\s+", " vs. ");
            if (tit.isEmpty()) continue;
            String liga = x.optString("camp").split(">")[0].trim();
            long t = 0;
            java.util.regex.Matcher m = P_HORA_EVIP.matcher(x.optString("hora"));
            if (m.find()) {
                java.util.Calendar c = java.util.Calendar.getInstance(TZ_AR);
                c.clear();
                int mes = Integer.parseInt(m.group(2)) - 1, anio = hoy.get(java.util.Calendar.YEAR);
                int dif = mes - hoy.get(java.util.Calendar.MONTH);
                if (dif < -6) anio++; else if (dif > 6) anio--;     // diciembre / enero
                c.set(anio, mes, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(4)), 0);
                t = c.getTimeInMillis();
            }
            JSONObject e = new JSONObject();
            e.put("fuente", fuente);
            e.put("clase", "AR");
            e.put("titulo", (liga.isEmpty() ? "" : liga + ": ") + tit);
            e.put("competencia", liga);
            e.put("t", t);
            e.put("opciones", new JSONArray().put(opcion("LPF Play", "", portada)));
            r.add(e);
        }
        return r;
    }

    // ------------------------------------------------------------------ mapa liga -> deporte

    /**
     * Nombre de la página de categoría de cada sitio -> deporte de la app.
     * VIPBox: /football-stream, /ice-hockey-stream…  ·  VIPRow: /sports-football-online, /sports-ice-hockey-online…
     * Las mezcladas («more», «others») no se usan: ahí hay de todo.
     */
    static final String[][] CATEGORIAS = {
            {"afl", "aussie-rules"}, {"aussie-rules", "aussie-rules"},
            {"nfl", "american-football"}, {"ncaaf", "american-football"}, {"american-football", "american-football"},
            {"baseball", "baseball"}, {"mlb", "baseball"}, {"basketball", "basketball"}, {"nba", "basketball"},
            {"boxing", "fighting"}, {"fight-sport", "fighting"}, {"fighting", "fighting"}, {"mma", "fighting"},
            {"ufc", "fighting"}, {"wwe", "fighting"}, {"cycling", "cycling"}, {"darts", "darts"},
            {"football", "football"}, {"soccer", "football"}, {"formula-1", "motorsport"}, {"f1", "motorsport"}, {"moto-racing", "motorsport"},
            {"motogp", "motorsport"}, {"moto-gp", "motorsport"}, {"motorsports", "motorsport"}, {"nascar", "motorsport"},
            {"golf", "golf"}, {"ice-hockey", "hockey"}, {"nhl", "hockey"}, {"rugby", "rugby"}, {"snooker", "snooker"},
            {"tennis", "tennis"}, {"volleyball", "volleyball"}, {"cricket", "cricket"}, {"handball", "handball"},
            {"horse-racing", "others"},
    };
    static final java.util.regex.Pattern P_CATEGORIA = java.util.regex.Pattern.compile(
            "href=\"/(?:sports-)?([a-z0-9-]+?)-(?:stream|online)\"");
    static final java.util.regex.Pattern P_SECCION = java.util.regex.Pattern.compile(
            "href=\"/([^\"/]+)/[^\"/]+-(?:stream-live|online-stream)\"");
    // Cada 12 h: el mapa cambia poco, pero las señales de un torneo (ATP Finals…) vienen y van.
    static final long MAPA_CADA_MS = 12L * 3600 * 1000;
    boolean armandoMapa;
    /** La app no está a la vista (estás en el navegador viendo un partido, o la tele en el protector). */
    volatile boolean pausada;

    static String deporteDeCategoria(String cat) {
        for (String[] c : CATEGORIAS) if (c[0].equals(cat)) return c[1];
        return null;
    }

    /** Una vez por día: recorre las páginas de categoría de cada sitio y arma «dominio|sección → deporte». */
    void actualizarMapaSiHaceFalta() {
        if (armandoMapa || sitiosVip.length == 0) return;
        long hace = System.currentTimeMillis() - prefs.getLong("mapaFecha", 0);
        // Recién actualizada a la versión con canales, todavía no hay ninguno guardado: se arma ya.
        if (hace < MAPA_CADA_MS && !prefs.getString("mapaLigas", "").isEmpty() && prefs.contains("canales")) return;
        armandoMapa = true;
        final String[][] lista = sitiosVip;
        new Thread(() -> {
            JSONObject mapa = new JSONObject();
            // Canales y señales fijas SIN horario (Sky F1, NFL RedZone, Fox AFL TV…): no están en «en vivo
            // ahora», solo en las páginas de categoría. Se juntan en esta misma pasada, sin bajar nada extra.
            JSONArray canales = new JSONArray();
            java.util.Set<String> canalesVistos = new java.util.HashSet<>();
            int paginas = 0;
            long inicio = SystemClock.elapsedRealtime();
            for (String[] s : lista) {
                try {
                    Uri u = Uri.parse(s[1]);
                    String base = u.getScheme() + "://" + u.getHost(), dom = dominioBase(u.getHost());
                    java.util.regex.Matcher mc = P_CATEGORIA.matcher(bajar(base + "/"));
                    // Todas las categorías: las conocidas aportan al mapa; las mezcladas («more», «others»)
                    // solo aportan canales (mezclan deportes, no sirven para decir de qué es cada liga).
                    java.util.LinkedHashMap<String, String> cats = new java.util.LinkedHashMap<>();
                    while (mc.find()) {
                        if (mc.group(1).startsWith("live-now")) continue;
                        String dep = deporteDeCategoria(mc.group(1));
                        String ruta = mc.group(0).substring(6, mc.group(0).length() - 1);   // "/football-stream"
                        cats.put(ruta, dep == null ? "" : dep);
                    }
                    for (java.util.Map.Entry<String, String> c : cats.entrySet()) {
                        // Saliste de la app (a mirar un partido): se corta para no competir con el video
                        // por el Wi-Fi; se vuelve a intentar la próxima vez (no se guarda un mapa a medias).
                        if (pausada) throw new InterruptedException("app en segundo plano");
                        try {
                            String pagina = bajar(base + c.getKey());
                            if (!c.getValue().isEmpty()) {
                                java.util.regex.Matcher ms = P_SECCION.matcher(pagina);
                                while (ms.find()) {
                                    String clave = dom + "|" + ms.group(1);
                                    if (!mapa.has(clave)) mapa.put(clave, c.getValue());
                                }
                            }
                            for (JSONObject e : eventosDe(base, pagina, s[0])) {
                                if (!e.optString("t").isEmpty()) continue;            // solo los sin horario
                                String id = e.optString("id"), clave = id.isEmpty() ? e.optString("href") : id;
                                if (canalesVistos.add(clave)) canales.put(e);
                            }
                            paginas++;
                            Thread.sleep(300);   // sin apuro: que no compita con la tele
                        } catch (Exception ignored) { }
                    }
                } catch (InterruptedException e) {
                    break;                                   // saliste de la app: se deja para otro momento
                } catch (Exception e) {
                    Log.i("PartidosTV", "mapa: no se pudo leer " + s[0] + ": " + e.getMessage());
                }
            }
            final boolean cortado = pausada;
            final String json = mapa.toString();
            String jc;
            try {
                jc = new JSONObject().put("ok", true).put("items", canales).toString();
            } catch (Exception e) {
                jc = "";
            }
            final String jsonCanales = jc;
            final int n = mapa.length(), nc = canales.length(), pags = paginas;
            final long ms = SystemClock.elapsedRealtime() - inicio;
            handler.post(() -> {
                armandoMapa = false;
                if (cortado) { Log.i("PartidosTV", "mapa: cortado al salir de la app, se reintenta después"); return; }
                if (n == 0) return;
                prefs.edit().putString("mapaLigas", json).putString("canales", jsonCanales)
                        .putLong("mapaFecha", System.currentTimeMillis()).apply();
                Log.i("PartidosTV", "mapa de ligas: " + n + " secciones, " + nc + " canales, de " + pags + " páginas en " + ms / 1000 + " s");
                volcarArchivo("mapa.json", json);
                volcarArchivo("canales.json", jsonCanales);
                if (uiLista) {
                    ui.evaluateJavascript("app.setMapa(" + JSONObject.quote(json) + ")", null);
                    enviarCanales();
                }
            });
        }, "mapa").start();
    }

    String segundos() {
        return String.valueOf(((SystemClock.elapsedRealtime() - inicioCarga) / 100) / 10.0);
    }

    void terminar(int t) {
        liberarLector();
        handler.postDelayed(() -> {
            if (t == turno && !viendoSitio) cargarSitio();
        }, AUTO_ACTUALIZAR_MS);
    }

    // ------------------------------------------------------------------ comunicación con la UI

    void estado(String texto) {
        if (!uiLista) return;
        ui.evaluateJavascript("app.setStatus(" + JSONObject.quote(texto) + ")", null);
    }

    /** Canales sin horario guardados en la última pasada por las categorías (ver actualizarMapaSiHaceFalta). */
    void enviarCanales() {
        if (!uiLista) return;
        String c = prefs.getString("canales", "");
        if (!c.isEmpty()) ui.evaluateJavascript("app.setCanales(" + JSONObject.quote(c) + ")", null);
    }

    void enviarSitios() {
        if (!uiLista) return;
        JSONArray a = new JSONArray();
        for (String[] s : sitios) a.put(s[0]);
        ui.evaluateJavascript("app.setSites(" + a + "," + sitio + ")", null);
    }

    void abrirEnNavegador(String url) {
        Uri u = Uri.parse(url);
        if (!"https".equals(u.getScheme()) || !permitido(u)) return;  // solo links de los sitios conocidos
        Intent i = new Intent(Intent.ACTION_VIEW, u);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No hay un navegador instalado en la tele", Toast.LENGTH_LONG).show();
        }
    }

    void verSitio(boolean ver) {
        viendoSitio = ver;
        ui.setVisibility(ver ? View.GONE : View.VISIBLE);
        lector.setVisibility(ver ? View.VISIBLE : View.INVISIBLE);
        lector.getSettings().setBlockNetworkImage(!ver);
        lector.setFocusable(ver);
        if (ver) {
            handler.removeCallbacksAndMessages(null);
            turno++;
            lector.onResume();
            lector.loadUrl(sitios[sitio][1]);
            lector.requestFocus();
            Toast.makeText(this, "Mostrando el sitio. Atrás para volver.", Toast.LENGTH_LONG).show();
        } else {
            ui.requestFocus();
            cargarSitio();
        }
    }

    class Puente {
        @JavascriptInterface
        public void abrir(String url) {
            handler.post(() -> abrirEnNavegador(url));
        }

        @JavascriptInterface
        public void actualizar() {
            handler.post(() -> {
                estado("Actualizando…");
                cargarSitio();
            });
        }

        @JavascriptInterface
        public void elegirSitio(int i) {
            handler.post(() -> {
                if (i < 0 || i >= sitios.length) return;
                sitio = i;
                prefs.edit().putInt("sitio", i).apply();
                enviarSitios();
                huboDatos = false;
                ui.evaluateJavascript("app.clear()", null);
                estado("Cargando " + sitios[sitio][0] + "…");
                cargarSitio();
            });
        }

        /** «Internacional» / «Argentina»: se recuerda, y se carga solo lo de ese grupo (menos trabajo para la tele). */
        @JavascriptInterface
        public void elegirGrupo(String g) {
            handler.post(() -> {
                boolean arg = "argentina".equals(g);
                prefs.edit().putString("grupo", arg ? "argentina" : "internacional").apply();
                if (arg == modoArgentina) return;
                modoArgentina = arg;
                estado(arg ? "Cargando Argentina…" : "Cargando " + sitios[sitio][0] + "…");
                cargarSitio();
            });
        }

        // Sin «verSitio»: mostrar el sitio dentro de la app permitiría ver los partidos acá adentro, y lo
        // acordado es que la app solo lista y abre en el navegador. Queda solo para diagnóstico por adb
        // (am start … --ez diagnosticoSitio true).
    }

    // ------------------------------------------------------------------ ciclo de vida y teclas

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && viendoSitio) {
            verSitio(false);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i("PartidosTV", "pausa (uiLista=" + uiLista + ")");
        pausada = true;
        handler.removeCallbacksAndMessages(null);   // mientras mirás el partido, no se actualiza nada
        turno++;
        if (!viendoSitio) liberarLector();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i("PartidosTV", "vuelve (uiLista=" + uiLista + ", pausada=" + pausada + ")");
        boolean veniaDePausa = pausada;
        pausada = false;
        if (uiLista && !viendoSitio) cargarSitio();   // al volver, lista fresca
        // Al abrir, Android a veces pausa y retoma la app antes de que la pantalla termine de cargar: la
        // pausa descartó la lectura que estaba en curso. Se vuelve a leer apenas la pantalla esté lista.
        else if (veniaDePausa && !viendoSitio) recargarAlCargarUi = true;
    }

    boolean recargarAlCargarUi;

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        lector.destroy();
        ui.destroy();
        super.onDestroy();
    }
}
