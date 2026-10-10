package com.ahsen.caisse;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_FILE = 1, REQ_CAM_PERM = 2;
    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private WebChromeClient.FileChooserParams pendingParams;

    // ── Verrouillage par empreinte ──
    private SharedPreferences prefs;
    private View lockView;
    private TextView lockText;
    private Button lockBtn;
    private boolean ar() { return "ar".equals(prefs.getString("lang", "fr")); }
    private boolean locked = false, skipNextLock = false, prompting = false;
    private long stoppedAt = 0;
    private static final long RELOCK_MS = 20_000;

    private int authenticators() {
        return Build.VERSION.SDK_INT >= 30
                ? BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL
                : BiometricManager.Authenticators.BIOMETRIC_WEAK;
    }
    private boolean biometricAvailable() {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }
    private boolean lockWanted() { return prefs.getBoolean("lock_enabled", true) && biometricAvailable(); }

    private View buildLockView() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundColor(Color.parseColor("#0F1115"));
        l.setClickable(true);
        TextView t = new TextView(this);
        lockText = t;
        t.setText("🔒\nCaisse verrouillée");
        t.setTextColor(Color.WHITE); t.setTextSize(22); t.setGravity(Gravity.CENTER);
        Button b = new Button(this);
        lockBtn = b;
        b.setText("Déverrouiller par empreinte");
        b.setOnClickListener(v -> authenticate());
        l.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = 48;
        l.addView(b, lp);
        return l;
    }

    private void lockNow() {
        if (!lockWanted()) return;
        locked = true;
        lockView.setVisibility(View.VISIBLE);
        refreshLockTexts();
        authenticate();
    }

    private void refreshLockTexts() {
        lockText.setText(ar() ? "🔒\nالصندوق مقفل" : "🔒\nCaisse verrouillée");
        lockBtn.setText(ar() ? "فتح القفل بالبصمة" : "Déverrouiller par empreinte");
    }

    private void authenticate() {
        if (prompting) return;
        prompting = true;
        BiometricPrompt.PromptInfo.Builder pi = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(ar() ? "الصندوق — معهد أحسن بودربالة" : "Caisse — Institut Ahsen Bouderbala")
                .setSubtitle(ar() ? "ضع إصبعك على المستشعر" : "Posez votre doigt sur le capteur")
                .setAllowedAuthenticators(authenticators());
        if (Build.VERSION.SDK_INT < 30) pi.setNegativeButtonText(ar() ? "إلغاء" : "Annuler");
        BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                        prompting = false; locked = false; lockView.setVisibility(View.GONE);
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) { prompting = false; }
                });
        prompt.authenticate(pi.build());
    }

    @Override
    protected void onStop() { super.onStop(); stoppedAt = System.currentTimeMillis(); }

    @Override
    protected void onStart() {
        super.onStart();
        if (skipNextLock) { skipNextLock = false; return; }
        if (!locked && stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > RELOCK_MS) lockNow();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("caisse_prefs", MODE_PRIVATE);
        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView = buildLockView();
        lockView.setVisibility(View.GONE);
        root.addView(lockView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this)).build();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Bridge(), "CaisseAndroid");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                pendingParams = p;
                if (p.isCaptureEnabled()
                        && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
                } else {
                    launchChooser(p);
                }
                return true;
            }
        });
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        if (lockWanted()) { locked = true; lockView.setVisibility(View.VISIBLE); refreshLockTexts(); web.post(this::authenticate); }
    }

    private void launchChooser(WebChromeClient.FileChooserParams p) {
        try {
            Intent intent;
            if (p.isCaptureEnabled()) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, "piece_" + System.currentTimeMillis() + ".jpg");
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            } else {
                cameraUri = null;
                intent = p.createIntent();
            }
            skipNextLock = true;
            startActivityForResult(intent, REQ_FILE);
        } catch (Exception e) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_CAM_PERM && pendingParams != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) launchChooser(pendingParams);
            else if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || filePathCallback == null) return;
        Uri[] result = null;
        if (res == Activity.RESULT_OK) {
            if (cameraUri != null) {
                result = new Uri[]{cameraUri};
            } else if (data != null) {
                if (data.getClipData() != null) {
                    ArrayList<Uri> l = new ArrayList<>();
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) l.add(data.getClipData().getItemAt(i).getUri());
                    result = l.toArray(new Uri[0]);
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
        } else if (cameraUri != null) {
            getContentResolver().delete(cameraUri, null, null);
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
        cameraUri = null;
    }

    @Override
    public void onBackPressed() {
        // La page gère ses propres boîtes de dialogue ; on quitte simplement l'app
        super.onBackPressed();
    }

    /** Pont JavaScript : enregistre dans « Téléchargements » et peut ouvrir le partage Android. */
    private class Bridge {
        @JavascriptInterface public boolean lockAvailable() { return biometricAvailable(); }
        @JavascriptInterface public boolean isLockEnabled() { return prefs.getBoolean("lock_enabled", true) && biometricAvailable(); }
        @JavascriptInterface public void setLang(String l) { prefs.edit().putString("lang", "ar".equals(l) ? "ar" : "fr").apply(); }
        @JavascriptInterface public void setLockEnabled(boolean on) { prefs.edit().putBoolean("lock_enabled", on).apply(); }

        /** Sauvegarde automatique : un seul fichier Download/Caisse/Caisse_auto.json, remplacé à chaque fois. */
        @JavascriptInterface
        public void autoBackup(String json) {
            try {
                ContentResolver cr = getContentResolver();
                Uri table = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri target = null;
                try (android.database.Cursor c = cr.query(table, new String[]{MediaStore.Downloads._ID},
                        MediaStore.Downloads.DISPLAY_NAME + "=? AND " + MediaStore.Downloads.RELATIVE_PATH + "=?",
                        new String[]{"Caisse_auto.json", "Download/Caisse/"}, null)) {
                    if (c != null && c.moveToFirst()) target = android.content.ContentUris.withAppendedId(table, c.getLong(0));
                }
                if (target == null) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, "Caisse_auto.json");
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Caisse");
                    target = cr.insert(table, v);
                }
                try (OutputStream os = cr.openOutputStream(target, "wt")) { os.write(json.getBytes("UTF-8")); }
            } catch (Exception ignored) { /* sauvegarde de confort : jamais bloquante */ }
        }

        @JavascriptInterface
        public void saveFile(String base64, String name, String mime, boolean share) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentResolver cr = getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, mime);
                v.put(MediaStore.Downloads.RELATIVE_PATH, "Download/Caisse");
                Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                try (OutputStream os = cr.openOutputStream(uri)) { os.write(bytes); }
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Enregistré : Téléchargements/Caisse/" + name, Toast.LENGTH_LONG).show();
                    if (share) {
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(mime);
                        i.putExtra(Intent.EXTRA_STREAM, uri);
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        skipNextLock = true;
                        startActivity(Intent.createChooser(i, "Envoyer le fichier"));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Échec : " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }
    }
}
