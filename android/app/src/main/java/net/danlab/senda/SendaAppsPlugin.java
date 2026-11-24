package net.danlab.senda;

import android.content.Intent;
import android.content.pm.PackageManager;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Senda (DanLab): para el apartado «Otras apps de DanLab».
 * Dice qué apps de la lista están instaladas en el móvil y las abre.
 */
@CapacitorPlugin(name = "SendaApps")
public class SendaAppsPlugin extends Plugin {

    /** { packages: ["net.danlab.x", ...] } → { installed: { "net.danlab.x": true, ... } } */
    @PluginMethod
    public void installed(PluginCall call) {
        JSObject out = new JSObject();
        JSArray list = call.getArray("packages");
        PackageManager pm = getContext().getPackageManager();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                String p = list.optString(i, null);
                if (p == null || p.isEmpty()) continue;
                boolean ok;
                try { ok = pm.getLaunchIntentForPackage(p) != null; } catch (Exception e) { ok = false; }
                out.put(p, ok);
            }
        }
        JSObject r = new JSObject();
        r.put("installed", out);
        call.resolve(r);
    }

    /** { package: "net.danlab.x" } → abre esa app */
    @PluginMethod
    public void open(PluginCall call) {
        String p = call.getString("package");
        if (p == null || p.isEmpty()) { call.reject("Falta el paquete"); return; }
        try {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(p);
            if (i == null) { call.reject("No está instalada"); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("No se pudo abrir", e);
        }
    }
}
