package eu.kodanetwork.mchost.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

import eu.kodanetwork.mchost.R;

/*
 * Copyright (c) 2026 KodaHosting
 *
 * Licensed under the GNU General Public License v3 (GPL-3.0) — see LICENSE
 */
public class AppLicenseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_license);

        ImageButton btnBack = findViewById(R.id.btn_back);
        if (btnBack != null) {
            btnBack.setOnClickListener(v -> finish());
        }

    }

    private void previewPdf(String filename) {
        try {
            java.io.File cacheDir = new java.io.File(getCacheDir(), "licenses");
            cacheDir.mkdirs();
            java.io.File pdfFile = new java.io.File(cacheDir, filename);
            
            
                java.io.InputStream in = getAssets().open("licenses/" + filename);
                java.io.FileOutputStream out = new java.io.FileOutputStream(pdfFile);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.flush();
                out.close();
            
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(this, "eu.kodanetwork.mchost.fileprovider", pdfFile);
            android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Failed to open PDF: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }

    private void downloadPdf(String filename, String displayName) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.content.ContentResolver resolver = getContentResolver();
                android.content.ContentValues contentValues = new android.content.ContentValues();
                contentValues.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName);
                contentValues.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/pdf");
                contentValues.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS);
                
                android.net.Uri uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues);
                if (uri != null) {
                    java.io.OutputStream out = resolver.openOutputStream(uri);
                    java.io.InputStream in = getAssets().open("licenses/" + filename);
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                    in.close();
                    if (out != null) {
                        out.close();
                    }
                    android.widget.Toast.makeText(this, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show();
                }
            } else {
                java.io.File dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                java.io.File file = new java.io.File(dir, displayName);
                java.io.OutputStream out = new java.io.FileOutputStream(file);
                java.io.InputStream in = getAssets().open("licenses/" + filename);
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                in.close();
                out.close();
                android.widget.Toast.makeText(this, "Saved to Downloads", android.widget.Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Error saving file: " + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        }
    }
}
