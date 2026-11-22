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
        // La versión 1 guardaba el índice con FCTV33 primero: se resetea a la nueva preferencia.
        if (prefs.getInt("version", 1) < 2) prefs.edit().putInt("version", 2).putInt("sitio", 0).apply();
        // Última lista de direcciones que se leyó del gist (si nunca se pudo, quedan las de fábrica).
        String[][][] guardada = interpretarConfig(prefs.getString("config", ""));
        if (guardada != null) aplicarConfig(guardada);
        else aplicarConfig(new String[][][]{SITIOS_FABRICA, VIP_FABRICA});
        sitio = Math.min(prefs.getInt("sitio", 0), sitios.length - 1);
        sitioVip = Math.min(prefs.getInt("sitioVip", 0), sitiosVip.length - 1);
        // Para pruebas por adb: am start ... --ei sitio 1 --ei sitioVip 1 (fuerza una fuente sin guardarla)
        Intent arranque = getIntent();
        if (arranque != null) {
            sitio = Math.max(0, Math.min(arranque.getIntExtra("sitio", sitio), sitios.length - 1));
            sitioVip = Math.max(0, Math.min(arranque.getIntExtra("sitioVip", sitioVip), sitiosVip.length - 1));
            volcar = arranque.getBooleanExtra("volcar", false);
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
                enviarSitios();
                enviarCanales();        // los canales 24 h guardados se ven desde el primer momento
                estado("Cargando " + sitios[sitio][0] + "…");
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
     * Devuelve {principales, vips} o null si el texto no sirve (así un error al editar no rompe nada).
     */
    static String[][][] interpretarConfig(String txt) {
        if (txt == null || txt.isEmpty()) return null;
        java.util.List<String[]> pr = new java.util.ArrayList<>(), vp = new java.util.ArrayList<>();
        java.util.regex.Pattern linea = java.util.regex.Pattern.compile(
                "^\\s*(principal|vip)\\s+(\\S+)\\s*=\\s*(https://[^\\s/]+\\.[^\\s/]+\\S*)\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String l : txt.split("\\r?\\n")) {
            if (l.trim().isEmpty() || l.trim().startsWith("#")) continue;
            java.util.regex.Matcher m = linea.matcher(l);
            if (!m.matches()) continue;
            String[] s = {m.group(2), m.group(3)};
            if ("principal".equalsIgnoreCase(m.group(1))) pr.add(s); else vp.add(s);
            if (pr.size() + vp.size() > 20) break;
        }
        if (pr.isEmpty()) return null;   // sin fuente principal no hay app
        return new String[][][]{pr.toArray(new String[0][]), vp.toArray(new String[0][])};
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
        for (String[][] grupo : new String[][][]{sitios, sitiosVip}) {
            for (String[] s : grupo) {
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

    /** "jack11eo.mpaver6fuiwild.my" -> "mpaver6fuiwild.my" */
    static String dominioBase(String host) {
        String[] p = host.split("\\.");
        if (p.length <= 2) return host;
        return p[p.length - 2] + "." + p[p.length - 1];
    }

    // ------------------------------------------------------------------ lectura de los sitios

    /** Arranca una actualización completa: primero la fuente principal, después VIPBox. */
    void cargarSitio() {
        probeOtroVip = false;
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
        if (viendoSitio) return;
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
        pausada = true;
        handler.removeCallbacksAndMessages(null);   // mientras mirás el partido, no se actualiza nada
        turno++;
        if (!viendoSitio) liberarLector();
    }

    @Override
    protected void onResume() {
        super.onResume();
        pausada = false;
        if (uiLista && !viendoSitio) cargarSitio();   // al volver, lista fresca
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        lector.destroy();
        ui.destroy();
        super.onDestroy();
    }
}
