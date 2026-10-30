package net.danlab.senda;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Senda (DanLab): descarga la versión nueva desde GitHub y abre directamente
 * el instalador de Android, como AVRadio. Solo hay que pulsar "Instalar".
 */
@CapacitorPlugin(name = "SendaUpdate")
public class SendaUpdatePlugin extends Plugin {

    private volatile boolean busy = false;

    private boolean allowed() {
        if (Build.VERSION.SDK_INT < 26) return true;
        return getContext().getPackageManager().canRequestPackageInstalls();
    }

    @PluginMethod
    public void canInstall(PluginCall call) {
        JSObject r = new JSObject();
        r.put("allowed", allowed());
        call.resolve(r);
    }

    /** Abre el ajuste "Instalar apps desconocidas" de Senda (solo hace falta una vez). */
    @PluginMethod
    public void openInstallSettings(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir el ajuste: " + e.getMessage());
        }
    }

    @PluginMethod
    public void downloadAndInstall(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null) { call.reject("Falta la dirección de la actualización"); return; }
        if (busy) { call.reject("Ya se está descargando la actualización", "BUSY"); return; }
        if (!allowed()) { call.reject("Falta el permiso para instalar", "NEEDS_PERMISSION"); return; }
        busy = true;
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File dir = new File(getContext().getCacheDir(), "actualizacion");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                File apk = new File(dir, "Senda.apk");
                if (apk.exists()) //noinspection ResultOfMethodCallIgnored
                    apk.delete();

                // GitHub redirige a otro servidor: se siguen las redirecciones a mano
                URL u = new URL(url);
                boolean ok = false;
                for (int hop = 0; hop < 6 && !ok; hop++) {
                    con = (HttpURLConnection) u.openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    con.setRequestProperty("User-Agent", "Senda-DanLab");
                    int code = con.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = con.getHeaderField("Location");
                        con.disconnect();
                        if (loc == null) throw new Exception("Redirección sin destino");
                        u = new URL(u, loc);
                        continue;
                    }
                    if (code != 200) throw new Exception("GitHub respondió " + code);
                    ok = true;
                }
                if (!ok) throw new Exception("Demasiadas redirecciones");
                long total = con.getContentLength();
                long done = 0;
                int lastPct = -1;
                byte[] buf = new byte[64 * 1024];
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            if (pct != lastPct) {
                                lastPct = pct;
                                JSObject p = new JSObject();
                                p.put("pct", pct);
                                notifyListeners("updateProgress", p);
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw new Exception("La descarga se cortó");

                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(uri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(install);
                call.resolve();
            } catch (Exception e) {
                call.reject("No se pudo descargar la actualización: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
                busy = false;
            }
        }).start();
    }
}
