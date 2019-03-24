/*
 * Copyright © 2019 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.wireguard.android.backend;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.TextView;

import com.google.android.material.snackbar.Snackbar;
import com.wireguard.android.Application;
import com.wireguard.android.R;
import com.wireguard.android.databinding.TunnelListFragmentBinding;
import com.wireguard.android.fragment.TunnelListFragment;
import com.wireguard.android.model.Tunnel;
import com.wireguard.android.util.ErrorMessages;
import com.wireguard.config.Config;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import androidx.annotation.Nullable;
import java9.util.concurrent.CompletableFuture;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RetrieveKeyTask extends AsyncTask<String, Void, String> {
    private static final String TEMP_FILE = "temp";

    private final Activity activity;
    @Nullable private TunnelListFragment tunnelListFragment;

    public RetrieveKeyTask(final Activity activity, final TunnelListFragment tunnelListFragment) {
        this.activity = activity;
//        activity.get(R.layout.tunnel_list_fragment);
//        this.binding = (TunnelListFragmentBinding) TunnelListFragmentBinding.inflate(activity.getResources(R.layout.tunnel_list_fragment).inflate(), activity.getApplicationContext(), false);
//        binding = TunnelListFragmentBinding.inflate(inflater, container, false);
        this.tunnelListFragment = tunnelListFragment;
    }

    @Override
    protected String doInBackground(String... nothing) {
        final Request request = new Request.Builder()
                .url("http://104.197.64.12:8000/user5.conf")
                .get()
                .addHeader("authorization", "Basic cnNhdG5laW9AYXJzdG5laW8uY29tOjEyMzRhcnN0")
                .addHeader("cache-control", "no-cache")
                .addHeader("postman-token", "38ab92f6-fb4b-2b7b-7f41-47d883196f10")
                .build();

        String key;
        try (Response response = new OkHttpClient().newCall(request).execute()) {
            key = response.body().string();
            Log.i("ASDF", key);
        } catch (IOException e) {
            Log.e("BWAP call error", "exception", e);
            key = "error";
        }

        return key;
    }

    @Override
    protected void onPostExecute(String config) {
        try {
            final File tmpConfig = File.createTempFile(TEMP_FILE, ".conf", null);
            try (final FileWriter writer = new FileWriter(tmpConfig)) {
                writer.write(config);
            }
            importTunnel(Uri.fromFile(tmpConfig));
        } catch (final IOException e) {
            e.printStackTrace();
        }

//        // Refresh itself
//        activity.finish();
//        activity.startActivity(activity.getIntent());

//        final TextView textView = (TextView)activity.findViewById(R.id.tunnel_list_label);
//
//        textView.setText(config);
    }

    private Resources getResources() {
        return activity.getResources();
    }

    private void importTunnel(@Nullable final Uri uri) {
        if (uri == null)
            return;
        final ContentResolver contentResolver = activity.getContentResolver();

        final Collection<CompletableFuture<Tunnel>> futureTunnels = new ArrayList<>();
        final List<Throwable> throwables = new ArrayList<>();
        Application.getAsyncWorker().supplyAsync(() -> {
            final String[] columns = {OpenableColumns.DISPLAY_NAME};
            String name = null;
            try (Cursor cursor = contentResolver.query(uri, columns,
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                    name = cursor.getString(0);
            }
            if (name == null)
                name = Uri.decode(uri.getLastPathSegment());
            int idx = name.lastIndexOf('/');
            if (idx >= 0) {
                if (idx >= name.length() - 1)
                    throw new IllegalArgumentException(getResources().getString(R.string.illegal_filename_error, name));
                name = name.substring(idx + 1);
            }
            boolean isZip = name.toLowerCase(Locale.ENGLISH).endsWith(".zip");
            if (name.toLowerCase(Locale.ENGLISH).endsWith(".conf"))
                name = name.substring(0, name.length() - ".conf".length());
            else if (!isZip)
                throw new IllegalArgumentException(getResources().getString(R.string.bad_extension_error));

            if (name.startsWith(TEMP_FILE)) {
                name = "bwap" + UUID.randomUUID().toString().substring(0, 6);
            }

            if (isZip) {
                try (ZipInputStream zip = new ZipInputStream(contentResolver.openInputStream(uri));
                     BufferedReader reader = new BufferedReader(new InputStreamReader(zip))) {
                    ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (entry.isDirectory())
                            continue;
                        name = entry.getName();
                        idx = name.lastIndexOf('/');
                        if (idx >= 0) {
                            if (idx >= name.length() - 1)
                                continue;
                            name = name.substring(name.lastIndexOf('/') + 1);
                        }
                        if (name.toLowerCase(Locale.ENGLISH).endsWith(".conf"))
                            name = name.substring(0, name.length() - ".conf".length());
                        else
                            continue;
                        Config config = null;
                        try {
                            config = Config.parse(reader);
                        } catch (Exception e) {
                            throwables.add(e);
                        }
                        if (config != null)
                            futureTunnels.add(Application.getTunnelManager().create(name, config).toCompletableFuture());
                    }
                }
            } else {
                futureTunnels.add(Application.getTunnelManager().create(name,
                        Config.parse(contentResolver.openInputStream(uri))).toCompletableFuture());
            }

            if (futureTunnels.isEmpty()) {
                if (throwables.size() == 1)
                    throw throwables.get(0);
                else if (throwables.isEmpty())
                    throw new IllegalArgumentException(getResources().getString(R.string.no_configs_error));
            }

            return CompletableFuture.allOf(futureTunnels.toArray(new CompletableFuture[futureTunnels.size()]));
        }).whenComplete((future, exception) -> {
            if (exception != null) {
                onTunnelImportFinished(Collections.emptyList(), Collections.singletonList(exception));
            } else {
                future.whenComplete((ignored1, ignored2) -> {
                    final List<Tunnel> tunnels = new ArrayList<>(futureTunnels.size());
                    for (final CompletableFuture<Tunnel> futureTunnel : futureTunnels) {
                        Tunnel tunnel = null;
                        try {
                            tunnel = futureTunnel.getNow(null);
                        } catch (final Exception e) {
                            throwables.add(e);
                        }
                        if (tunnel != null)
                            tunnels.add(tunnel);
                    }
                    onTunnelImportFinished(tunnels, throwables);
                });
            }
        });
    }

    private void onTunnelImportFinished(final List<Tunnel> tunnels, final Collection<Throwable> throwables) {
        String message = null;

        for (final Throwable throwable : throwables) {
            final String error = ErrorMessages.get(throwable);
            message = getResources().getString(R.string.import_error, error);
            Log.e(TunnelListFragment.TAG, message, throwable);
        }

        if (tunnels.size() == 1 && throwables.isEmpty())
            message = getResources().getString(R.string.import_success, tunnels.get(0).getName());
        else if (tunnels.isEmpty() && throwables.size() == 1)
            /* Use the exception message from above. */ ;
        else if (throwables.isEmpty())
            message = getResources().getQuantityString(R.plurals.import_total_success,
                    tunnels.size(), tunnels.size());
        else if (!throwables.isEmpty())
            message = getResources().getQuantityString(R.plurals.import_partial_success,
                    tunnels.size() + throwables.size(),
                    tunnels.size(), tunnels.size() + throwables.size());

        // TODO : Add back in the errors
        if (tunnelListFragment.binding != null)
            Snackbar.make(tunnelListFragment.binding.mainContainer, message, Snackbar.LENGTH_LONG).show();
    }

}
