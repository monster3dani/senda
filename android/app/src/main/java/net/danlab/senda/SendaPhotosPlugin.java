package net.danlab.senda;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * Senda (DanLab): busca en la galería las fotos hechas entre dos horas
 * y las devuelve reducidas para mostrarlas y guardarlas en la app.
 */
@CapacitorPlugin(
    name = "SendaPhotos",
    permissions = {
        @Permission(alias = "photos", strings = { Manifest.permission.READ_MEDIA_IMAGES }),
        @Permission(alias = "photosLegacy", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class SendaPhotosPlugin extends Plugin {

    private String alias() {
        return Build.VERSION.SDK_INT >= 33 ? "photos" : "photosLegacy";
    }

    private long readLong(PluginCall call, String key) {
        Object v = call.getData().opt(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try { return Long.parseLong((String) v); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    @PluginMethod
    public void findPhotos(PluginCall call) {
        if (getPermissionState(alias()) != PermissionState.GRANTED) {
            requestPermissionForAlias(alias(), call, "photosPermissionCallback");
            return;
        }
        runFind(call, true);
    }

    @PermissionCallback
    private void photosPermissionCallback(PluginCall call) {
        // Con acceso limitado (Android 14) también se intenta: se verán las fotos permitidas
        runFind(call, getPermissionState(alias()) == PermissionState.GRANTED);
    }

    private void runFind(final PluginCall call, final boolean granted) {
        new Thread(() -> {
            try {
                long start = readLong(call, "start");
                long end = readLong(call, "end");
                ContentResolver resolver = getContext().getContentResolver();
                Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                String[] projection = {
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.ORIENTATION
                };
                String selection = "(" + MediaStore.Images.Media.DATE_TAKEN + " >= ? AND " + MediaStore.Images.Media.DATE_TAKEN + " <= ?)"
                    + " OR (" + MediaStore.Images.Media.DATE_TAKEN + " IS NULL AND " + MediaStore.Images.Media.DATE_ADDED + " >= ? AND " + MediaStore.Images.Media.DATE_ADDED + " <= ?)";
                String[] args = { String.valueOf(start), String.valueOf(end), String.valueOf(start / 1000), String.valueOf(end / 1000) };
                JSArray photos = new JSArray();
                Cursor c = resolver.query(collection, projection, selection, args, MediaStore.Images.Media.DATE_TAKEN + " ASC");
                if (c != null) {
                    try {
                        int iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                        int iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN);
                        int iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);
                        int iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                        int iRot = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION);
                        while (c.moveToNext()) {
                            long id = c.getLong(iId);
                            long taken = c.isNull(iTaken) ? c.getLong(iAdded) * 1000L : c.getLong(iTaken);
                            String name = c.isNull(iName) ? "" : c.getString(iName);
                            int rot = c.isNull(iRot) ? 0 : c.getInt(iRot);
                            Uri uri = ContentUris.withAppendedId(collection, id);
                            String lower = name.toLowerCase();
                            JSObject p = new JSObject();
                            p.put("id", String.valueOf(id));
                            p.put("uri", uri.toString());
                            p.put("time", taken);
                            p.put("name", name);
                            p.put("rotation", rot);
                            p.put("screenshot", lower.contains("screenshot") || lower.contains("captura"));
                            photos.put(p);
                        }
                    } finally {
                        c.close();
                    }
                }
                if (!granted && photos.length() == 0) {
                    call.reject("Senda no tiene permiso para ver tus fotos.", "PERMISSION_DENIED");
                    return;
                }
                JSObject ret = new JSObject();
                ret.put("photos", photos);
                ret.put("granted", granted);
                call.resolve(ret);
            } catch (SecurityException e) {
                call.reject("Senda no tiene permiso para ver tus fotos.", "PERMISSION_DENIED");
            } catch (Exception e) {
                call.reject("No se pudieron buscar las fotos: " + e.getMessage());
            }
        }).start();
    }

    @PluginMethod
    public void readPhoto(final PluginCall call) {
        final String uriStr = call.getString("uri");
        final int max = call.getInt("max", 1600);
        final int quality = call.getInt("quality", 82);
        final int rotation = call.getInt("rotation", 0);
        if (uriStr == null) { call.reject("Falta la foto"); return; }
        new Thread(() -> {
            try {
                ContentResolver resolver = getContext().getContentResolver();
                Uri uri = Uri.parse(uriStr);
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream is1 = resolver.openInputStream(uri);
                BitmapFactory.decodeStream(is1, null, bounds);
                if (is1 != null) is1.close();
                int sample = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2;
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                InputStream is2 = resolver.openInputStream(uri);
                Bitmap bmp = BitmapFactory.decodeStream(is2, null, opts);
                if (is2 != null) is2.close();
                if (bmp == null) { call.reject("No se pudo abrir la foto"); return; }
                float k = Math.min(1f, (float) max / Math.max(bmp.getWidth(), bmp.getHeight()));
                Matrix m = new Matrix();
                m.postScale(k, k);
                if (rotation != 0) m.postRotate(rotation);
                Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                out.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                if (out != bmp) out.recycle();
                bmp.recycle();
                JSObject ret = new JSObject();
                ret.put("data", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP));
                call.resolve(ret);
            } catch (SecurityException e) {
                call.reject("Senda no tiene permiso para ver esa foto.", "PERMISSION_DENIED");
            } catch (Exception e) {
                call.reject("No se pudo leer la foto: " + e.getMessage());
            }
        }).start();
    }
}
