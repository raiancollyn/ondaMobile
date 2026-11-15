package net.dpbr.onda;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Verifica as releases do GitHub e deixa o APK novo pronto para instalação.
 * A verificação é silenciosa: falhas de rede não interrompem a chamada nem o app.
 */
final class UpdateManager {
    private static final String TAG = "OndaUpdate";
    private static final String RELEASES_URL =
        "https://api.github.com/repos/raiancollyn/ondaMobile/releases/latest";
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String PREFS = "onda.updates";
    private static final String KEY_LAST_CHECK = "last_check";
    private static final String KEY_SKIPPED = "skipped";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private final Activity activity;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences preferences;
    private final DownloadManager downloadManager;

    private long downloadId = -1L;
    private String downloadFileName;
    private boolean awaitingInstallPermission;
    private String pendingUrl;
    private String pendingTag;

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null ||
                !DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) {
                return;
            }

            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (downloadId < 0 || id != downloadId) {
                return;
            }

            try {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                try (Cursor cursor = downloadManager.query(query)) {
                    if (cursor == null || !cursor.moveToFirst()) {
                        return;
                    }

                    int status = cursor.getInt(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status != DownloadManager.STATUS_SUCCESSFUL) {
                        Toast.makeText(activity, "Não foi possível baixar a atualização.",
                            Toast.LENGTH_LONG).show();
                        resetDownload();
                        return;
                    }
                }

                File file = downloadedFile();
                if (file != null && file.isFile() && file.length() > 0) {
                    install(file);
                } else {
                    Uri uri = downloadManager.getUriForDownloadedFile(id);
                    if (uri != null) {
                        install(uri);
                    } else {
                        Toast.makeText(activity, "O APK baixado não foi encontrado.",
                            Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Exception error) {
                Log.w(TAG, "Falha ao concluir o download da atualização.", error);
                Toast.makeText(activity, "Não foi possível instalar a atualização.",
                    Toast.LENGTH_LONG).show();
            } finally {
                resetDownload();
            }
        }
    };

    UpdateManager(Activity activity) {
        this.activity = activity;
        this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.downloadManager =
            (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(downloadReceiver, filter);
        }
    }

    void checkForUpdates(boolean manual) {
        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong(KEY_LAST_CHECK, 0L);
        if (!manual && lastCheck > 0 && now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }

        preferences.edit().putLong(KEY_LAST_CHECK, now).apply();
        executor.execute(() -> {
            Release release = fetchLatestRelease();
            if (release == null) {
                return;
            }

            String current = currentVersion();
            if (compareVersions(release.tag, current) <= 0) {
                return;
            }

            if (release.tag.equals(preferences.getString(KEY_SKIPPED, ""))) {
                return;
            }

            mainHandler.post(() -> showUpdateDialog(release));
        });
    }

    void onResume() {
        if (!awaitingInstallPermission || pendingUrl == null) {
            return;
        }

        if (!canInstallPackages()) {
            return;
        }

        awaitingInstallPermission = false;
        String url = pendingUrl;
        String tag = pendingTag;
        pendingUrl = null;
        pendingTag = null;
        startDownload(url, tag);
    }

    void destroy() {
        executor.shutdownNow();
        try {
            activity.unregisterReceiver(downloadReceiver);
        } catch (Exception ignored) {
            // O receiver já foi removido com a activity.
        }
    }

    private Release fetchLatestRelease() {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(RELEASES_URL);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Onda-Android");

            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            JSONObject release = new JSONObject(body.toString());
            String tag = release.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                return null;
            }

            String apkUrl = null;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null) {
                        continue;
                    }

                    String name = asset.optString("name", "");
                    String assetUrl = asset.optString("browser_download_url", "");
                    if (name.endsWith(".apk") && !assetUrl.isEmpty()) {
                        apkUrl = assetUrl;
                        if (name.contains(tag)) {
                            break;
                        }
                    }
                }
            }

            return apkUrl == null ? null : new Release(tag, apkUrl);
        } catch (Exception error) {
            Log.i(TAG, "Não foi possível verificar atualizações: " + error.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void showUpdateDialog(Release release) {
        if (activity.isFinishing() ||
            (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
            return;
        }

        new AlertDialog.Builder(activity)
            .setTitle("Atualização disponível")
            .setMessage("A versão " + release.tag + " do Onda está disponível.\n\n"
                + "Você está usando a versão " + currentVersion() + ".")
            .setPositiveButton("Atualizar", (dialog, which) -> requestDownload(release))
            .setNegativeButton("Depois", (dialog, which) ->
                preferences.edit().putString(KEY_SKIPPED, release.tag).apply())
            .show();
    }

    private void requestDownload(Release release) {
        if (!canInstallPackages()) {
            awaitingInstallPermission = true;
            pendingUrl = release.url;
            pendingTag = release.tag;

            new AlertDialog.Builder(activity)
                .setTitle("Permitir instalação")
                .setMessage("Para instalar a atualização, permita que o Onda instale "
                    + "aplicativos desconhecidos nas configurações do Android.")
                .setPositiveButton("Abrir configurações", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (Exception error) {
                        Toast.makeText(activity,
                            "Abra as configurações e permita a instalação pelo Onda.",
                            Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancelar", (dialog, which) -> {
                    awaitingInstallPermission = false;
                    pendingUrl = null;
                    pendingTag = null;
                })
                .show();
            return;
        }

        startDownload(release.url, release.tag);
    }

    private void startDownload(String url, String tag) {
        if (url == null || tag == null || downloadManager == null) {
            return;
        }

        try {
            String fileName = "Onda-" + tag.replaceAll("[^A-Za-z0-9._-]", "-") + ".apk";
            File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (directory == null) {
                directory = activity.getCacheDir();
            }

            File target = new File(directory, fileName);
            if (target.exists()) {
                // O DownloadManager pode recusar um destino já existente.
                // Se não for possível apagar, o receiver usa a URI do sistema.
                //noinspection ResultOfMethodCallIgnored
                target.delete();
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle("Atualização do Onda " + tag);
            request.setDescription("Baixando a nova versão do Onda...");
            request.setMimeType(APK_MIME);
            request.setNotificationVisibility(
                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(
                activity, Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(false);

            downloadFileName = fileName;
            downloadId = downloadManager.enqueue(request);
            Toast.makeText(activity,
                "Baixando a atualização. Você poderá instalar quando terminar.",
                Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Log.w(TAG, "Falha ao iniciar o download da atualização.", error);
            Toast.makeText(activity, "Não foi possível baixar a atualização.",
                Toast.LENGTH_LONG).show();
        }
    }

    private File downloadedFile() {
        if (downloadFileName == null) {
            return null;
        }

        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        return directory == null ? null : new File(directory, downloadFileName);
    }

    private void install(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(
                activity,
                activity.getPackageName() + ".fileprovider",
                file);
            install(uri);
        } catch (Exception error) {
            Log.w(TAG, "Não foi possível expor o APK para o instalador.", error);
            Toast.makeText(activity, "Não foi possível abrir o instalador.",
                Toast.LENGTH_LONG).show();
        }
    }

    private void install(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception error) {
            Log.w(TAG, "Não foi possível iniciar o instalador do Android.", error);
            Toast.makeText(activity, "Não foi possível abrir o instalador.",
                Toast.LENGTH_LONG).show();
        }
    }

    private boolean canInstallPackages() {
        if (Build.VERSION.SDK_INT < 26) {
            return true;
        }

        return activity.getPackageManager().canRequestPackageInstalls();
    }

    private String currentVersion() {
        try {
            PackageInfo info = activity.getPackageManager()
                .getPackageInfo(activity.getPackageName(), 0);
            return info.versionName == null ? "0" : info.versionName;
        } catch (Exception error) {
            return "0";
        }
    }

    private void resetDownload() {
        downloadId = -1L;
        downloadFileName = null;
    }

    private static int compareVersions(String left, String right) {
        String[] leftParts = normalizeVersion(left).split("\\.");
        String[] rightParts = normalizeVersion(right).split("\\.");
        int length = Math.max(leftParts.length, rightParts.length);

        for (int index = 0; index < length; index++) {
            int leftValue = index < leftParts.length ? parseVersionPart(leftParts[index]) : 0;
            int rightValue = index < rightParts.length ? parseVersionPart(rightParts[index]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }

        return 0;
    }

    private static String normalizeVersion(String value) {
        String normalized = value == null ? "0" : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }

        return normalized.isEmpty() ? "0" : normalized.toLowerCase(Locale.ROOT);
    }

    private static int parseVersionPart(String value) {
        try {
            String digits = value.replaceAll("[^0-9].*$", "");
            return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static final class Release {
        final String tag;
        final String url;

        Release(String tag, String url) {
            this.tag = tag;
            this.url = url;
        }
    }
}
