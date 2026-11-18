package net.danlab.senda;

import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Senda (DanLab): recibe los archivos GPX, TCX o FIT que otra app (Zepp, por ejemplo)
 * comparte con Senda, o que se abren con Senda desde el gestor de archivos.
 */
@CapacitorPlugin(name = "SendaShare")
public class SendaSharePlugin extends Plugin {

    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private final List<JSObject> pending = new ArrayList<>();

    @Override
    public void load() {
        if (getActivity() != null) handle(getActivity().getIntent());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        handle(intent);
    }

    /** Devuelve los archivos recibidos que la app aún no ha abierto, y los olvida. */
    @PluginMethod
    public void getPending(PluginCall call) {
        JSArray arr = new JSArray();
        synchronized (pending) {
            for (JSObject f : pending) arr.put(f);
            pending.clear();
        }
        JSObject r = new JSObject();
        r.put("files", arr);
        call.resolve(r);
    }

    @SuppressWarnings("deprecation")
    private void handle(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String a = intent.getAction();
        final List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(a)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
            ArrayList<Uri> l = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        } else if (Intent.ACTION_VIEW.equals(a) && intent.getData() != null) {
            uris.add(intent.getData());
        } else {
            return;
        }
        if (uris.isEmpty()) {
            ClipData clip = intent.getClipData();
            if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        // Para no volver a abrir el mismo archivo si Android recrea la pantalla
        intent.setAction(Intent.ACTION_MAIN);
        if (uris.isEmpty()) return;
        new Thread(() -> {
            int n = 0;
            for (Uri u : uris) {
                JSObject f = read(u);
                if (f != null) { synchronized (pending) { pending.add(f); } n++; }
            }
            JSObject ev = new JSObject();
            ev.put("count", n);
            notifyListeners("filesShared", ev, true);
        }).start();
    }

    private JSObject read(Uri uri) {
        String name = null;
        try (Cursor c = getContext().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = c.getString(i);
            }
        } catch (Exception ignored) { }
        if (name == null) {
            String p = uri.getLastPathSegment();
            name = p != null ? p.substring(p.lastIndexOf('/') + 1) : "actividad";
        }
        try (InputStream in = getContext().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int r;
            long total = 0;
            while ((r = in.read(buf)) > 0) {
                total += r;
                if (total > MAX_BYTES) return null;
                out.write(buf, 0, r);
            }
            JSObject f = new JSObject();
            f.put("name", name);
            f.put("data", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
            return f;
        } catch (Exception e) {
            return null;
        }
    }
}
