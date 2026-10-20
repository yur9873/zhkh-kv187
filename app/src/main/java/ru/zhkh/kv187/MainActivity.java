package ru.zhkh.kv187;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.util.ArrayList;

/** Оболочка: показывает локальную страницу калькулятора (assets/index.html). */
public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAM_PERM = 2;
    private static final int REQ_WEB_CAM = 3;

    private WebView web;
    private ValueCallback<Uri[]> filePathCallback;
    private Uri cameraUri;
    private PermissionRequest pendingWebPerm;
    private Runnable afterCamPerm;
    private TextRecognizer recognizer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, u));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             final FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                final boolean images = isImage(params.getAcceptTypes());
                Runnable go = new Runnable() {
                    @Override
                    public void run() {
                        launchChooser(params, images);
                    }
                };
                if (images && checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    afterCamPerm = go;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM_PERM);
                } else {
                    go.run();
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        boolean video = false;
                        for (String r : request.getResources()) {
                            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                                video = true;
                            }
                        }
                        if (!video) {
                            request.deny();
                            return;
                        }
                        if (checkSelfPermission(Manifest.permission.CAMERA)
                                == PackageManager.PERMISSION_GRANTED) {
                            request.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                        } else {
                            pendingWebPerm = request;
                            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_WEB_CAM);
                        }
                    }
                });
            }
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    private static boolean isImage(String[] accept) {
        if (accept == null) return false;
        for (String a : accept) {
            if (a != null && a.startsWith("image/")) return true;
        }
        return false;
    }

    private void launchChooser(WebChromeClient.FileChooserParams params, boolean images) {
        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType(images ? "image/*" : "*/*");
        Intent chooser = Intent.createChooser(content, images ? "Фото счётчика" : "Файл");

        cameraUri = null;
        if (images && checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "meter_" + System.currentTimeMillis() + ".jpg");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cameraUri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (cameraUri != null) {
                    Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                    cam.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    if (params.isCaptureEnabled()) {
                        try {
                            startActivityForResult(cam, REQ_FILE);
                            return; // сразу камера
                        } catch (Exception ignored) {
                            // нет приложения камеры: покажем обычный выбор
                        }
                    }
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
                }
            } catch (Exception e) {
                cameraUri = null;
            }
        }
        try {
            startActivityForResult(chooser, REQ_FILE);
        } catch (Exception e) {
            dropCameraRow();
            finishChooser(null);
        }
    }

    private void finishChooser(Uri[] result) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(result);
            filePathCallback = null;
        }
    }

    private void dropCameraRow() {
        if (cameraUri != null) {
            try {
                getContentResolver().delete(cameraUri, null, null);
            } catch (Exception ignored) {
            }
            cameraUri = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILE) return;
        Uri[] res = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getData() != null) {
                res = new Uri[]{data.getData()};
            } else if (data != null && data.getClipData() != null) {
                ClipData cd = data.getClipData();
                ArrayList<Uri> list = new ArrayList<>();
                for (int i = 0; i < cd.getItemCount(); i++) {
                    list.add(cd.getItemAt(i).getUri());
                }
                res = list.toArray(new Uri[0]);
            } else if (cameraUri != null) {
                res = new Uri[]{cameraUri};
            }
        }
        boolean usedCamera = false;
        if (res != null && cameraUri != null) {
            for (Uri u : res) {
                if (cameraUri.equals(u)) usedCamera = true;
            }
        }
        if (!usedCamera) {
            dropCameraRow(); // пустая запись под снимок не нужна
        }
        cameraUri = null;
        finishChooser(res);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_CAM_PERM) {
            Runnable r = afterCamPerm;
            afterCamPerm = null;
            if (r != null) r.run(); // дальше без камеры, если отказали: останется выбор из галереи
        } else if (requestCode == REQ_WEB_CAM && pendingWebPerm != null) {
            if (granted) {
                pendingWebPerm.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingWebPerm.deny();
            }
            pendingWebPerm = null;
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }

    private void toast(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, text, Toast.LENGTH_LONG).show();
            }
        });
    }


    /** Результат распознавания возвращается в страницу: window.__ocrDone(id, {lines:[...]}) */
    private void deliver(final String id, final String json) {
        if (id == null || !id.matches("[0-9]{1,9}")) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript("window.__ocrDone('" + id + "'," + json + ")", null);
            }
        });
    }

    /** Мост для страницы: сохранение копии в «Загрузки» и распознавание фото. */
    public class Bridge {
        @JavascriptInterface
        public void ocr(String base64, final String id) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bmp == null) {
                    deliver(id, "{\"error\":\"decode\"}");
                    return;
                }
                if (recognizer == null) {
                    recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                }
                recognizer.process(InputImage.fromBitmap(bmp, 0))
                        .addOnSuccessListener(text -> {
                            try {
                                JSONArray lines = new JSONArray();
                                for (Text.TextBlock block : text.getTextBlocks()) {
                                    for (Text.Line line : block.getLines()) {
                                        JSONObject o = new JSONObject();
                                        o.put("t", line.getText());
                                        Rect r = line.getBoundingBox();
                                        if (r != null) {
                                            o.put("x", r.left);
                                            o.put("y", r.top);
                                            o.put("w", r.width());
                                            o.put("h", r.height());
                                        }
                                        lines.put(o);
                                    }
                                }
                                JSONObject res = new JSONObject();
                                res.put("lines", lines);
                                deliver(id, res.toString());
                            } catch (Exception e) {
                                deliver(id, "{\"error\":\"parse\"}");
                            }
                        })
                        .addOnFailureListener(e -> deliver(id, "{\"error\":\"ocr\"}"));
            } catch (Throwable t) {
                deliver(id, "{\"error\":\"fail\"}");
            }
        }

        @JavascriptInterface
        public void save(String name, String mime, String base64) {
            try {
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (u == null) throw new Exception("no uri");
                OutputStream o = getContentResolver().openOutputStream(u);
                if (o == null) throw new Exception("no stream");
                o.write(bytes);
                o.close();
                toast("Сохранено в «Загрузки»: " + name);
            } catch (Exception e) {
                toast("Не удалось сохранить файл");
            }
        }
    }
}
