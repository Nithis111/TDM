package com.github.axet.torrentclient.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import com.github.axet.androidlibrary.net.HttpClient;
import com.github.axet.androidlibrary.widgets.WebViewCustom;

import org.apache.commons.io.IOUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SearchEngine {
    public static final String TAG = SearchEngine.class.getSimpleName();

    Map<String, Object> map = new LinkedHashMap<>();

    public JSONObject loadJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            map = WebViewCustom.toMap(obj);
            return obj;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public JSONObject loadUrl(Context context, String url) {
        try {
            Uri uri = Uri.parse(url);
            String json;

            if (uri.getScheme().equals(ContentResolver.SCHEME_FILE)) {
                InputStream is = context.getContentResolver().openInputStream(uri);
                json = IOUtils.toString(is, MainApplication.UTF8);
            } else if (uri.getScheme().equals(ContentResolver.SCHEME_ANDROID_RESOURCE)) { // app assests
                InputStream is = context.getContentResolver().openInputStream(uri);
                json = IOUtils.toString(is, MainApplication.UTF8);
            } else if (Build.VERSION.SDK_INT >= 21 && uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) { // saf
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                ContentResolver resolver = context.getContentResolver();
                resolver.takePersistableUriPermission(uri, takeFlags);
                InputStream is = context.getContentResolver().openInputStream(uri);
                json = IOUtils.toString(is, MainApplication.UTF8);
            } else {
                HttpClient client = new HttpClient();
                HttpClient.DownloadResponse w = client.getResponse(null, url);
                w.download();
                if (w.getError() != null)
                    throw new RuntimeException(w.getError() + ": " + url);
                json = w.getHtml();
            }

            return loadJson(json);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public Set<String> keySet() {
        return map.keySet();
    }

    public Map<String, String> getMap(String key) {
        return (Map<String, String>) map.get(key);
    }

    public String getString(String key) {
        return (String) map.get(key);
    }

    public String save() {
        try {
            JSONObject json = (JSONObject) WebViewCustom.toJSON(map);
            return json.toString(2);
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public String getName() {
        return getString("name");
    }

    public int getVersion() {
        return ((Number) map.get("version")).intValue();
    }
}
