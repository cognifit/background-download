/*
       Licensed to the Apache Software Foundation (ASF) under one
       or more contributor license agreements.  See the NOTICE file
       distributed with this work for additional information
       regarding copyright ownership.  The ASF licenses this file
       to you under the Apache License, Version 2.0 (the
       "License"); you may not use this file except in compliance
       with the License.  You may obtain a copy of the License at

         http://www.apache.org/licenses/LICENSE-2.0

       Unless required by applicable law or agreed to in writing,
       software distributed under the License is distributed on an
       "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
       KIND, either express or implied.  See the License for the
       specific language governing permissions and limitations
       under the License.
 */
package org.apache.cordova.backgroundDownload;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ForegroundInfo;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Based on DownloadManager which is intended to be used for long-running HTTP downloads. Support of Android 2.3. (API 9) and later
 * http://developer.android.com/reference/android/app/DownloadManager.html TODO: concurrent downloads support
 */

public class BackgroundDownload extends CordovaPlugin {
    private static final String TAG = "BackgroundDownload";
    private static final boolean BYPASS_DOWNLOAD_MANAGER_FOR_TESTING = false;
    private static final long DOWNLOAD_ID_UNDEFINED = -1;
    private static final String TEMP_DOWNLOAD_FILE_EXTENSION = ".temp";
    private static final long DOWNLOAD_PROGRESS_UPDATE_TIMEOUT = 1000;
    private static final String TRANSPORT_DOWNLOAD_MANAGER = "download_manager";
    private static final String TRANSPORT_WORKMANAGER_HTTP = "workmanager_http";
    private static final String WORK_NOTIFICATION_CHANNEL_ID = "background_download_fallback";
    private static final String WORK_NOTIFICATION_CHANNEL_NAME = "Background downloads";
    private static final int WORK_NOTIFICATION_BASE_ID = 41000;
    private static final int HTTP_CONNECT_TIMEOUT_MS = 15000;
    private static final int HTTP_READ_TIMEOUT_MS = 30000;
    private static final int HTTP_PROGRESS_MIN_BYTES = 64 * 1024;
    private static final long HTTP_PROGRESS_MIN_INTERVAL_MS = 1000L;
    private static final int HTTP_MAX_REDIRECTS = 5;
    private static final String WORK_INPUT_URI = "uri";
    private static final String WORK_INPUT_TEMP_PATH = "tempFilePath";
    private static final String WORK_INPUT_TITLE = "notificationTitle";
    private static final String WORK_PROGRESS_BYTES_RECEIVED = "bytesReceived";
    private static final String WORK_PROGRESS_TOTAL_BYTES = "totalBytes";
    private static final String WORK_OUTPUT_MESSAGE = "message";
    private static final String WORK_OUTPUT_EXCEPTION_CLASS = "exceptionClass";
    private static final String WORK_OUTPUT_HTTP_STATUS = "httpStatusCode";
    private static final String WORK_OUTPUT_HTTP_MESSAGE = "httpStatusMessage";
    private static final String WORK_OUTPUT_FINAL_URI = "finalUri";

    protected class Download {

        private String filePath;
        private String tempFilePath;
        private final String fallbackTempFilePath;
        private String uriString;
        private CallbackContext callbackContext; // The callback context from which we were invoked.
        private CallbackContext callbackContextDownloadStart; // The callback context from which we started file download command.
        private long downloadId = DOWNLOAD_ID_UNDEFINED;
        private Timer timerProgressUpdate = null;
        private String destinationMode = "unknown";
        private String transportMode = TRANSPORT_DOWNLOAD_MANAGER;
        private String fallbackWorkId = null;
        private String notificationTitle = "";
        private boolean stopRequested = false;
        private long lastProgressBytesReceived = Long.MIN_VALUE;
        private long lastProgressTotalBytes = Long.MIN_VALUE;
        private JSONObject fallbackTriggerDiagnostics = null;

        public Download(String uriString, String filePath,
                        CallbackContext callbackContext) {
            this.setUriString(uriString);
            this.setFilePath(filePath);
            this.fallbackTempFilePath = filePath + TEMP_DOWNLOAD_FILE_EXTENSION;
            this.setTempFilePath(this.fallbackTempFilePath);
            this.setCallbackContext(callbackContext);
            this.setCallbackContextDownloadStart(callbackContext);
        }

        public String getFilePath() {
            return filePath;
        }

        public void setFilePath(String filePath) {
            this.filePath = filePath;
        }

        public String getUriString() {
            return uriString;
        }

        public void setUriString(String uriString) {
            this.uriString = uriString;
        }

        public String getTempFilePath() {
            return tempFilePath;
        }

        public void setTempFilePath(String tempFilePath) {
            this.tempFilePath = tempFilePath;
        }

        public String getFallbackTempFilePath() {
            return fallbackTempFilePath;
        }

        public CallbackContext getCallbackContext() {
            return callbackContext;
        }

        public void setCallbackContext(CallbackContext callbackContext) {
            this.callbackContext = callbackContext;
        }

        public CallbackContext getCallbackContextDownloadStart() {
            return callbackContextDownloadStart;
        }

        public void setCallbackContextDownloadStart(
                CallbackContext callbackContextDownloadStart) {
            this.callbackContextDownloadStart = callbackContextDownloadStart;
        }

        public long getDownloadId() {
            return downloadId;
        }

        public void setDownloadId(long downloadId) {
            this.downloadId = downloadId;
        }

        public Timer getTimerProgressUpdate() {
            return timerProgressUpdate;
        }

        public void setTimerProgressUpdate(Timer TimerProgressUpdate) {
            this.timerProgressUpdate = TimerProgressUpdate;
        }

        public String getDestinationMode() {
            return destinationMode;
        }

        public void setDestinationMode(String destinationMode) {
            this.destinationMode = destinationMode;
        }

        public String getTransportMode() {
            return transportMode;
        }

        public void setTransportMode(String transportMode) {
            this.transportMode = transportMode;
        }

        public boolean isUsingWorkManagerFallback() {
            return TRANSPORT_WORKMANAGER_HTTP.equals(transportMode);
        }

        public String getFallbackWorkId() {
            return fallbackWorkId;
        }

        public void setFallbackWorkId(String fallbackWorkId) {
            this.fallbackWorkId = fallbackWorkId;
        }

        public String getNotificationTitle() {
            return notificationTitle;
        }

        public void setNotificationTitle(String notificationTitle) {
            this.notificationTitle = notificationTitle;
        }

        public boolean isStopRequested() {
            return stopRequested;
        }

        public void setStopRequested(boolean stopRequested) {
            this.stopRequested = stopRequested;
        }

        public long getLastProgressBytesReceived() {
            return lastProgressBytesReceived;
        }

        public void setLastProgressBytesReceived(long lastProgressBytesReceived) {
            this.lastProgressBytesReceived = lastProgressBytesReceived;
        }

        public long getLastProgressTotalBytes() {
            return lastProgressTotalBytes;
        }

        public void setLastProgressTotalBytes(long lastProgressTotalBytes) {
            this.lastProgressTotalBytes = lastProgressTotalBytes;
        }

        public JSONObject getFallbackTriggerDiagnostics() {
            return fallbackTriggerDiagnostics;
        }

        public void setFallbackTriggerDiagnostics(JSONObject fallbackTriggerDiagnostics) {
            this.fallbackTriggerDiagnostics = fallbackTriggerDiagnostics;
        }
    }

    HashMap<String, Download> activDownloads = new HashMap<String, Download>();

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        try {
            if (action.equals("startAsync")) {
                startAsync(args, callbackContext);
                return true;
            }
            if (action.equals("stop")) {
                stop(args, callbackContext);
                return true;
            }
            return false; // invalid action
        } catch (Exception ex) {
            callbackContext.error(ex.getMessage());
        }
        return true;
    }

    private void startAsync(JSONArray args, CallbackContext callbackContext) throws JSONException {
        if (activDownloads.size() == 0) {
            // required to receive notification when download is completed
            final IntentFilter intentFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            ContextCompat.registerReceiver(
                    cordova.getActivity(),
                    receiver,
                    intentFilter,
                    ContextCompat.RECEIVER_NOT_EXPORTED
            );
        }

        Download curDownload = new Download(args.get(0).toString(), args.get(1).toString(), callbackContext);
        String notificationTitle = args.get(2).toString();
        curDownload.setNotificationTitle(notificationTitle);

        if (activDownloads.containsKey(curDownload.getUriString())) {
            return;
        }

        activDownloads.put(curDownload.getUriString(), curDownload);
        Uri source = Uri.parse(curDownload.getUriString());

        if (BYPASS_DOWNLOAD_MANAGER_FOR_TESTING) {
            try {
                curDownload.setTransportMode(TRANSPORT_WORKMANAGER_HTTP);
                curDownload.setTempFilePath(curDownload.getFallbackTempFilePath());
                curDownload.setDestinationMode("workmanager_temp_file");
                startWorkManagerFallback(curDownload, null);
                StartProgressTracking(curDownload);
                Log.w(TAG, "Bypassing DownloadManager for testing uri=" + curDownload.getUriString());
                return;
            } catch (Exception ex) {
                activDownloads.remove(curDownload.getUriString());
                callbackContext.error("Failed to start WorkManager fallback in bypass mode: " + ex.getMessage());
                return;
            }
        }

        if (reattachToFallbackDownload(curDownload)) {
            StartProgressTracking(curDownload);
            return;
        }

        // attempt to attach to active download for this file (download started and we close/open the app)
        curDownload.setDownloadId(findActiveDownload(curDownload.getUriString()));

        if (curDownload.getDownloadId() == DOWNLOAD_ID_UNDEFINED) {
            File downloadManagerTempFile = getDownloadManagerDestinationFile(curDownload);
            curDownload.setTempFilePath(Uri.fromFile(downloadManagerTempFile).toString());

            // make sure file does not exist, in other case DownloadManager will fail
            deleteFileIfExists(downloadManagerTempFile);

            DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request request = new DownloadManager.Request(source);
            request.setTitle(notificationTitle);
            request.setVisibleInDownloadsUi(false);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                curDownload.setDestinationMode("external_public_downloads");
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS,
                        downloadManagerTempFile.getName()
                );
            } else {
                curDownload.setDestinationMode("external_app_files_downloads");
                request.setDestinationInExternalFilesDir(
                        this.cordova.getActivity(),
                        Environment.DIRECTORY_DOWNLOADS,
                        downloadManagerTempFile.getName()
                );
            }
            Log.i(TAG, "Enqueueing download uri=" + curDownload.getUriString() + " tempPath=" + curDownload.getTempFilePath() + " finalPath=" + curDownload.getFilePath());
            curDownload.setDownloadId(mgr.enqueue(request));

        } else if (checkDownloadCompleted(curDownload.getDownloadId())) {
            return;
        }

        // custom logic to track file download progress
        StartProgressTracking(curDownload);
    }

    private void StartProgressTracking(final Download curDownload) {
        // already started
        if (curDownload.getTimerProgressUpdate() != null) {
            return;
        }
        final DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        curDownload.setTimerProgressUpdate(new Timer());
        curDownload.getTimerProgressUpdate().schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    if (curDownload.isUsingWorkManagerFallback()) {
                        pollWorkManagerProgress(curDownload);
                    } else {
                        pollDownloadManagerProgress(curDownload, mgr);
                    }
                } catch (Exception ex) {
                    Log.w(TAG, "Progress polling failed for uri=" + curDownload.getUriString() + " transport=" + curDownload.getTransportMode(), ex);
                }
            }
        }, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT, DOWNLOAD_PROGRESS_UPDATE_TIMEOUT);
    }

    private void pollDownloadManagerProgress(Download curDownload, DownloadManager mgr) {
        DownloadManager.Query q = new DownloadManager.Query();
        q.setFilterById(curDownload.getDownloadId());
        Cursor cursor = mgr.query(q);
        try {
            if (!cursor.moveToFirst()) {
                return;
            }
            long bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            sendProgressUpdate(curDownload, bytesDownloaded, bytesTotal);
        } catch (IllegalArgumentException e) {
            // ignored
        } finally {
            cursor.close();
        }
    }

    private void pollWorkManagerProgress(Download curDownload) {
        if (TextUtils.isEmpty(curDownload.getFallbackWorkId())) {
            return;
        }

        WorkInfo workInfo;
        try {
            workInfo = WorkManager.getInstance(this.cordova.getContext())
                    .getWorkInfoById(UUID.fromString(curDownload.getFallbackWorkId()))
                    .get(2, TimeUnit.SECONDS);
        } catch (Exception ex) {
            Log.w(TAG, "Unable to query fallback worker status for uri=" + curDownload.getUriString(), ex);
            return;
        }

        if (workInfo == null) {
            return;
        }

        Data progress = workInfo.getProgress();
        long bytesReceived = progress.getLong(WORK_PROGRESS_BYTES_RECEIVED, 0L);
        long totalBytes = progress.getLong(WORK_PROGRESS_TOTAL_BYTES, -1L);
        sendProgressUpdate(curDownload, bytesReceived, totalBytes);

        if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
            copyTempFileToActualFile(curDownload);
            CleanUp(curDownload);
        } else if (workInfo.getState() == WorkInfo.State.FAILED) {
            JSONObject payload = createWorkManagerFailurePayload(curDownload, workInfo);
            Log.e(TAG, "Fallback HTTP download failed diagnostics=" + payload.toString());
            curDownload.getCallbackContextDownloadStart().error(payload);
            cleanFallbackTempFile(curDownload);
            CleanUp(curDownload);
        } else if (workInfo.getState() == WorkInfo.State.CANCELLED) {
            cleanFallbackTempFile(curDownload);
            CleanUp(curDownload);
        }
    }

    private void sendProgressUpdate(Download curDownload, long bytesDownloaded, long bytesTotal) {
        if (bytesDownloaded == curDownload.getLastProgressBytesReceived()
                && bytesTotal == curDownload.getLastProgressTotalBytes()) {
            return;
        }

        curDownload.setLastProgressBytesReceived(bytesDownloaded);
        curDownload.setLastProgressTotalBytes(bytesTotal);

        try {
            JSONObject jsonProgress = new JSONObject();
            jsonProgress.put("bytesReceived", bytesDownloaded);
            jsonProgress.put("totalBytesToReceive", bytesTotal);
            JSONObject obj = new JSONObject();
            obj.put("progress", jsonProgress);
            PluginResult progressUpdate = new PluginResult(PluginResult.Status.OK, obj);
            progressUpdate.setKeepCallback(true);
            curDownload.getCallbackContextDownloadStart().sendPluginResult(progressUpdate);
        } catch (JSONException e) {
            Log.w(TAG, "Failed to send progress callback for uri=" + curDownload.getUriString(), e);
        }
    }

    private void CleanUp(Download curDownload) {

        if (curDownload.getTimerProgressUpdate() != null) {
            curDownload.getTimerProgressUpdate().cancel();
            curDownload.setTimerProgressUpdate(null);
        }

        if (!curDownload.isUsingWorkManagerFallback() && curDownload.getDownloadId() != DOWNLOAD_ID_UNDEFINED) {
            DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            mgr.remove(curDownload.getDownloadId());
        }
        activDownloads.remove(curDownload.getUriString());

        if (activDownloads.size() == 0) {
            try {
                cordova.getActivity().unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // this is fine, receiver was not registered
            }
        }

    }

    private String getUserFriendlyReason(int reason) {
        switch (reason) {
            case DownloadManager.ERROR_CANNOT_RESUME:
                return "ERROR_CANNOT_RESUME";
            case DownloadManager.ERROR_DEVICE_NOT_FOUND:
                return "ERROR_DEVICE_NOT_FOUND";
            case DownloadManager.ERROR_FILE_ALREADY_EXISTS:
                return "ERROR_FILE_ALREADY_EXISTS";
            case DownloadManager.ERROR_FILE_ERROR:
                return "ERROR_FILE_ERROR";
            case DownloadManager.ERROR_HTTP_DATA_ERROR:
                return "ERROR_HTTP_DATA_ERROR";
            case DownloadManager.ERROR_INSUFFICIENT_SPACE:
                return "ERROR_INSUFFICIENT_SPACE";
            case DownloadManager.ERROR_TOO_MANY_REDIRECTS:
                return "ERROR_TOO_MANY_REDIRECTS";
            case DownloadManager.ERROR_UNHANDLED_HTTP_CODE:
                return "ERROR_UNHANDLED_HTTP_CODE";
            case DownloadManager.ERROR_UNKNOWN:
                return "ERROR_UNKNOWN";
            default:
                return "";
        }
    }

    private String getUserFriendlyStatus(int status) {
        switch (status) {
            case DownloadManager.STATUS_FAILED:
                return "STATUS_FAILED";
            case DownloadManager.STATUS_PAUSED:
                return "STATUS_PAUSED";
            case DownloadManager.STATUS_PENDING:
                return "STATUS_PENDING";
            case DownloadManager.STATUS_RUNNING:
                return "STATUS_RUNNING";
            case DownloadManager.STATUS_SUCCESSFUL:
                return "STATUS_SUCCESSFUL";
            default:
                return "STATUS_UNKNOWN";
        }
    }

    private JSONObject createFailurePayload(Context context, Cursor cursor, Download curDownload, int status, int reason) throws JSONException {
        JSONObject payload = new JSONObject();
        payload.put("message", "Download operation failed");
        payload.put("status", status);
        payload.put("statusLabel", getUserFriendlyStatus(status));
        payload.put("reason", reason);
        payload.put("reasonLabel", getUserFriendlyReason(reason));
        payload.put("transportMode", TRANSPORT_DOWNLOAD_MANAGER);

        if (curDownload != null) {
            payload.put("downloadId", curDownload.getDownloadId());
            payload.put("uri", curDownload.getUriString());
            payload.put("filePath", curDownload.getFilePath());
            payload.put("tempFilePath", curDownload.getTempFilePath());
            payload.put("destinationMode", curDownload.getDestinationMode());
        }

        int idxLocalUri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        int idxLocalFilename = cursor.getColumnIndex("local_filename");
        int idxBytesDownloaded = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        int idxBytesTotal = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES);
        int idxMediaType = cursor.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE);
        int idxTitle = cursor.getColumnIndex(DownloadManager.COLUMN_TITLE);

        if (idxLocalUri >= 0 && !cursor.isNull(idxLocalUri)) {
            payload.put("localUri", cursor.getString(idxLocalUri));
        }
        if (idxLocalFilename >= 0 && !cursor.isNull(idxLocalFilename)) {
            payload.put("localFilename", cursor.getString(idxLocalFilename));
        }
        if (idxBytesDownloaded >= 0) {
            payload.put("bytesDownloaded", cursor.getLong(idxBytesDownloaded));
        }
        if (idxBytesTotal >= 0) {
            payload.put("bytesTotal", cursor.getLong(idxBytesTotal));
        }
        if (idxMediaType >= 0 && !cursor.isNull(idxMediaType)) {
            payload.put("mediaType", cursor.getString(idxMediaType));
        }
        if (idxTitle >= 0 && !cursor.isNull(idxTitle)) {
            payload.put("title", cursor.getString(idxTitle));
        }

        JSONObject androidState = new JSONObject();
        androidState.put("sdkInt", Build.VERSION.SDK_INT);
        androidState.put("release", Build.VERSION.RELEASE);
        androidState.put("targetSdk", context.getApplicationInfo().targetSdkVersion);
        androidState.put("packageName", context.getPackageName());
        payload.put("android", androidState);

        JSONObject permissionState = new JSONObject();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            permissionState.put("writeExternalStorage", context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
            permissionState.put("readExternalStorage", context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
        } else {
            permissionState.put("writeExternalStorage", "pre_runtime_permissions");
            permissionState.put("readExternalStorage", "pre_runtime_permissions");
        }
        payload.put("permissions", permissionState);

        JSONObject filesystemState = new JSONObject();
        if (curDownload != null) {
            File targetFile = new File(Uri.parse(curDownload.getFilePath()).getPath());
            File tempTargetFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
            File tempParent = tempTargetFile.getParentFile();
            File finalParent = targetFile.getParentFile();
            File externalDownloadsDir = this.cordova.getActivity().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);

            filesystemState.put("tempFileExists", tempTargetFile.exists());
            filesystemState.put("tempFileLength", tempTargetFile.exists() ? tempTargetFile.length() : -1);
            filesystemState.put("tempFileAbsolutePath", tempTargetFile.getAbsolutePath());
            filesystemState.put("tempParentExists", tempParent != null && tempParent.exists());
            filesystemState.put("tempParentCanWrite", tempParent != null && tempParent.canWrite());
            filesystemState.put("tempParentAbsolutePath", tempParent != null ? tempParent.getAbsolutePath() : JSONObject.NULL);
            filesystemState.put("finalParentExists", finalParent != null && finalParent.exists());
            filesystemState.put("finalParentCanWrite", finalParent != null && finalParent.canWrite());
            filesystemState.put("finalParentAbsolutePath", finalParent != null ? finalParent.getAbsolutePath() : JSONObject.NULL);
            filesystemState.put("externalFilesDownloadsPath", externalDownloadsDir != null ? externalDownloadsDir.getAbsolutePath() : JSONObject.NULL);
        }
        payload.put("filesystem", filesystemState);

        return payload;
    }

    private JSONObject createHttpProbePayload(String uriString) {
        JSONObject payload = new JSONObject();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(uriString);
            payload.put("host", url.getHost());
            payload.put("protocol", url.getProtocol());

            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("Range", "bytes=0-0");
            connection.connect();

            payload.put("responseCode", connection.getResponseCode());
            payload.put("responseMessage", connection.getResponseMessage());
            payload.put("contentType", connection.getContentType() != null ? connection.getContentType() : JSONObject.NULL);
            payload.put("contentLength", connection.getContentLengthLong());
            payload.put("location", connection.getHeaderField("Location") != null ? connection.getHeaderField("Location") : JSONObject.NULL);
            payload.put("server", connection.getHeaderField("Server") != null ? connection.getHeaderField("Server") : JSONObject.NULL);
        } catch (Exception ex) {
            try {
                payload.put("probeError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            } catch (JSONException ignored) {
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return payload;
    }

    private void stop(JSONArray args, CallbackContext callbackContext) throws JSONException {

        Download curDownload = activDownloads.get(args.get(0).toString());
        if (curDownload == null) {
            callbackContext.error("download requst not found");
            return;
        }

        curDownload.setStopRequested(true);
        if (curDownload.isUsingWorkManagerFallback()) {
            if (!TextUtils.isEmpty(curDownload.getFallbackWorkId())) {
                WorkManager.getInstance(this.cordova.getContext()).cancelWorkById(UUID.fromString(curDownload.getFallbackWorkId()));
            }
            cleanFallbackTempFile(curDownload);
        } else {
            DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
            mgr.remove(curDownload.getDownloadId());
            deleteFileIfExists(new File(Uri.parse(curDownload.getTempFilePath()).getPath()));
        }
        CleanUp(curDownload);
        callbackContext.success();
    }

    private File getDownloadManagerDestinationFile(Download curDownload) {
        File requestedTempFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
        String tempFileName = requestedTempFile.getName();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File publicDownloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (publicDownloadsDir == null) {
                throw new IllegalStateException("Public downloads directory is unavailable");
            }
            return new File(publicDownloadsDir, tempFileName);
        } else {
            File externalFilesDir = this.cordova.getActivity().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (externalFilesDir == null) {
                throw new IllegalStateException("External files downloads directory is unavailable");
            }
            return new File(externalFilesDir, tempFileName);
        }
    }

    private long findActiveDownload(String uri) {

        DownloadManager mgr = (DownloadManager) cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);

        long downloadId = DOWNLOAD_ID_UNDEFINED;

        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterByStatus(DownloadManager.STATUS_PAUSED | DownloadManager.STATUS_PENDING | DownloadManager.STATUS_RUNNING | DownloadManager.STATUS_SUCCESSFUL);
        Cursor cur = mgr.query(query);
        int idxId = cur.getColumnIndex(DownloadManager.COLUMN_ID);
        int idxUri = cur.getColumnIndex(DownloadManager.COLUMN_URI);
        for (cur.moveToFirst(); !cur.isAfterLast(); cur.moveToNext()) {
            if (uri.equals(cur.getString(idxUri))) {
                downloadId = cur.getLong(idxId);
                break;
            }
        }
        cur.close();

        return downloadId;
    }

    private Boolean checkDownloadCompleted(long id) {
        DownloadManager mgr = (DownloadManager) this.cordova.getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Query query = new DownloadManager.Query();
        query.setFilterById(id);
        Cursor cur = mgr.query(query);
        int idxStatus = cur.getColumnIndex(DownloadManager.COLUMN_STATUS);
        int idxURI = cur.getColumnIndex(DownloadManager.COLUMN_URI);

        try {
            if (cur.moveToFirst()) {
                int status = cur.getInt(idxStatus);
                String uri = cur.getString(idxURI);
                Download curDownload = activDownloads.get(uri);
                if (status == DownloadManager.STATUS_SUCCESSFUL && curDownload != null) {
                    copyTempFileToActualFile(curDownload);
                    CleanUp(curDownload);
                    return true;
                }
            }
            return false;
        } finally {
            cur.close();
        }
    }

    private boolean reattachToFallbackDownload(Download curDownload) {
        try {
            List<WorkInfo> workInfos = WorkManager.getInstance(this.cordova.getContext())
                    .getWorkInfosForUniqueWork(getFallbackWorkName(curDownload.getUriString()))
                    .get(2, TimeUnit.SECONDS);
            for (WorkInfo workInfo : workInfos) {
                if (workInfo.getState() == WorkInfo.State.ENQUEUED
                        || workInfo.getState() == WorkInfo.State.RUNNING
                        || workInfo.getState() == WorkInfo.State.BLOCKED) {
                    curDownload.setTransportMode(TRANSPORT_WORKMANAGER_HTTP);
                    curDownload.setFallbackWorkId(workInfo.getId().toString());
                    curDownload.setTempFilePath(curDownload.getFallbackTempFilePath());
                    curDownload.setDestinationMode("workmanager_temp_file");
                    Log.i(TAG, "Reattached to fallback worker for uri=" + curDownload.getUriString() + " workId=" + curDownload.getFallbackWorkId());
                    return true;
                }
                if (workInfo.getState() == WorkInfo.State.SUCCEEDED) {
                    curDownload.setTransportMode(TRANSPORT_WORKMANAGER_HTTP);
                    curDownload.setFallbackWorkId(workInfo.getId().toString());
                    curDownload.setTempFilePath(curDownload.getFallbackTempFilePath());
                    copyTempFileToActualFile(curDownload);
                    CleanUp(curDownload);
                    return true;
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "Unable to query existing fallback work for uri=" + curDownload.getUriString(), ex);
        }
        return false;
    }

    private boolean shouldFallbackToWorkManager(Cursor cursor, int status, int reason) {
        if (status != DownloadManager.STATUS_FAILED || reason != DownloadManager.ERROR_UNKNOWN) {
            return false;
        }

        int idxBytesDownloaded = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR);
        long bytesDownloaded = idxBytesDownloaded >= 0 ? cursor.getLong(idxBytesDownloaded) : 0L;

        int idxLocalUri = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI);
        String localUri = idxLocalUri >= 0 && !cursor.isNull(idxLocalUri) ? cursor.getString(idxLocalUri) : null;

        return bytesDownloaded <= 0L && TextUtils.isEmpty(localUri);
    }

    private void startWorkManagerFallback(Download curDownload, JSONObject downloadManagerFailurePayload) throws JSONException {
        Log.w(TAG, "Falling back to WorkManager HTTP download for uri=" + curDownload.getUriString());
        curDownload.setFallbackTriggerDiagnostics(downloadManagerFailurePayload);

        deleteFileIfExists(new File(Uri.parse(curDownload.getTempFilePath()).getPath()));
        curDownload.setTempFilePath(curDownload.getFallbackTempFilePath());
        deleteFileIfExists(new File(Uri.parse(curDownload.getTempFilePath()).getPath()));
        curDownload.setTransportMode(TRANSPORT_WORKMANAGER_HTTP);
        curDownload.setDestinationMode("workmanager_temp_file");
        curDownload.setDownloadId(DOWNLOAD_ID_UNDEFINED);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        Data inputData = new Data.Builder()
                .putString(WORK_INPUT_URI, curDownload.getUriString())
                .putString(WORK_INPUT_TEMP_PATH, Uri.parse(curDownload.getTempFilePath()).getPath())
                .putString(WORK_INPUT_TITLE, curDownload.getNotificationTitle())
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(HttpDownloadWorker.class)
                .setConstraints(constraints)
                .setInputData(inputData)
                .addTag(getFallbackWorkTag(curDownload.getUriString()))
                .build();

        WorkManager.getInstance(this.cordova.getContext()).enqueueUniqueWork(
                getFallbackWorkName(curDownload.getUriString()),
                ExistingWorkPolicy.REPLACE,
                request
        );
        curDownload.setFallbackWorkId(request.getId().toString());
    }

    private JSONObject createWorkManagerFailurePayload(Download curDownload, WorkInfo workInfo) {
        JSONObject payload = new JSONObject();
        Data output = workInfo.getOutputData();
        try {
            payload.put("message", emptyToDefault(output.getString(WORK_OUTPUT_MESSAGE), "Download operation failed"));
            payload.put("status", DownloadManager.STATUS_FAILED);
            payload.put("statusLabel", getUserFriendlyStatus(DownloadManager.STATUS_FAILED));
            payload.put("reason", DownloadManager.ERROR_UNKNOWN);
            payload.put("reasonLabel", getUserFriendlyReason(DownloadManager.ERROR_UNKNOWN));
            payload.put("transportMode", TRANSPORT_WORKMANAGER_HTTP);
            payload.put("workState", workInfo.getState().name());
            payload.put("uri", curDownload.getUriString());
            payload.put("filePath", curDownload.getFilePath());
            payload.put("tempFilePath", curDownload.getTempFilePath());
            payload.put("bytesDownloaded", output.getLong(WORK_PROGRESS_BYTES_RECEIVED, curDownload.getLastProgressBytesReceived() == Long.MIN_VALUE ? 0L : curDownload.getLastProgressBytesReceived()));
            payload.put("bytesTotal", output.getLong(WORK_PROGRESS_TOTAL_BYTES, curDownload.getLastProgressTotalBytes() == Long.MIN_VALUE ? -1L : curDownload.getLastProgressTotalBytes()));

            if (!TextUtils.isEmpty(output.getString(WORK_OUTPUT_EXCEPTION_CLASS))) {
                payload.put("exceptionClass", output.getString(WORK_OUTPUT_EXCEPTION_CLASS));
            }
            if (output.getInt(WORK_OUTPUT_HTTP_STATUS, -1) >= 0) {
                payload.put("httpStatusCode", output.getInt(WORK_OUTPUT_HTTP_STATUS, -1));
            }
            if (!TextUtils.isEmpty(output.getString(WORK_OUTPUT_HTTP_MESSAGE))) {
                payload.put("httpStatusMessage", output.getString(WORK_OUTPUT_HTTP_MESSAGE));
            }
            if (!TextUtils.isEmpty(output.getString(WORK_OUTPUT_FINAL_URI))) {
                payload.put("finalUri", output.getString(WORK_OUTPUT_FINAL_URI));
            }
            if (curDownload.getFallbackTriggerDiagnostics() != null) {
                payload.put("downloadManagerFailure", curDownload.getFallbackTriggerDiagnostics());
            }
        } catch (JSONException jsonException) {
            Log.w(TAG, "Unable to build fallback failure payload", jsonException);
        }
        return payload;
    }

    private String getFallbackWorkName(String uri) {
        return "background-download:" + Integer.toHexString(uri.hashCode());
    }

    private String getFallbackWorkTag(String uri) {
        return "background-download-tag:" + Integer.toHexString(uri.hashCode());
    }

    private void cleanFallbackTempFile(Download curDownload) {
        deleteFileIfExists(new File(Uri.parse(curDownload.getFallbackTempFilePath()).getPath()));
    }

    private static void deleteFileIfExists(File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "Unable to delete file " + file.getAbsolutePath());
        }
    }

    private static String emptyToDefault(String value, String defaultValue) {
        return TextUtils.isEmpty(value) ? defaultValue : value;
    }

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(final Context context, Intent intent) {

            final DownloadManager mgr = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);

            final long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(downloadId);
            final Cursor cursor = mgr.query(query);
            int idxURI = cursor.getColumnIndex(DownloadManager.COLUMN_URI);
            cursor.moveToFirst();
            Download curDownload = null;
            boolean cleanupHandledAsync = false;

            try {
                String uri = cursor.getString(idxURI);
                curDownload = activDownloads.get(uri);

                long receivedID = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                query.setFilterById(receivedID);
                int idxStatus = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                int idxReason = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);

                if (cursor.moveToFirst()) {
                    final int status = cursor.getInt(idxStatus);
                    final int reason = cursor.getInt(idxReason);
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        if (curDownload != null) {
                            copyTempFileToActualFile(curDownload);
                        }
                    } else if (curDownload != null && shouldFallbackToWorkManager(cursor, status, reason)) {
                        final Download downloadRef = curDownload;
                        final JSONObject payload = createFailurePayload(context, cursor, curDownload, status, reason);
                        cleanupHandledAsync = true;
                        cursor.close();
                        mgr.remove(downloadId);
                        cordova.getThreadPool().execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    payload.put("httpProbe", createHttpProbePayload(downloadRef.getUriString()));
                                    startWorkManagerFallback(downloadRef, payload);
                                    Log.w(TAG, "DownloadManager failed with ERROR_UNKNOWN and zero progress, using WorkManager fallback for uri=" + downloadRef.getUriString());
                                } catch (Exception ex) {
                                    try {
                                        payload.put("fallbackStartError", ex.getClass().getSimpleName() + ": " + ex.getMessage());
                                    } catch (JSONException ignored) {
                                    }
                                    Log.e(TAG, "Failed to start WorkManager fallback diagnostics=" + payload.toString(), ex);
                                    downloadRef.getCallbackContextDownloadStart().error(payload);
                                    cleanFallbackTempFile(downloadRef);
                                    CleanUp(downloadRef);
                                }
                            }
                        });
                        return;
                    } else {
                        final JSONObject payload = createFailurePayload(context, cursor, curDownload, status, reason);
                        final Download downloadRef = curDownload;
                        final int reasonRef = reason;
                        cleanupHandledAsync = true;
                        cursor.close();
                        cordova.getThreadPool().execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (reasonRef == DownloadManager.ERROR_UNKNOWN && downloadRef != null) {
                                        payload.put("httpProbe", createHttpProbePayload(downloadRef.getUriString()));
                                    }
                                } catch (JSONException ignored) {
                                }
                                Log.e(TAG, "Download failed diagnostics=" + payload.toString());
                                if (downloadRef != null) {
                                    downloadRef.getCallbackContextDownloadStart().error(payload);
                                    CleanUp(downloadRef);
                                }
                            }
                        });
                        return;
                    }
                } else if (curDownload != null && !curDownload.isStopRequested()) {
                    curDownload.getCallbackContextDownloadStart().error("cancelled or terminated");
                }
                cursor.close();
            } catch (Exception ex) {
                if (curDownload != null) {
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("message", ex.getMessage());
                        payload.put("exceptionClass", ex.getClass().getName());
                        payload.put("downloadId", curDownload.getDownloadId());
                        payload.put("uri", curDownload.getUriString());
                        payload.put("filePath", curDownload.getFilePath());
                        payload.put("tempFilePath", curDownload.getTempFilePath());
                    } catch (JSONException jsonException) {
                    }
                    Log.e(TAG, "Download exception diagnostics=" + payload.toString(), ex);
                    if (!curDownload.isStopRequested()) {
                        curDownload.getCallbackContextDownloadStart().error(payload);
                    }
                } else {
                    for (Download download : activDownloads.values()) {
                        if (download.getDownloadId() == downloadId) {
                            if (!download.isStopRequested()) {
                                download.getCallbackContextDownloadStart().error("_DOWNLOAD_FAIL_");
                            }
                        }
                    }
                }
            } finally {
                if (!cleanupHandledAsync && curDownload != null) {
                    CleanUp(curDownload);
                } else if (!cleanupHandledAsync) {
                    for (Download download : activDownloads.values()) {
                        if (download.getDownloadId() == downloadId) {
                            CleanUp(download);
                        }
                    }
                }
                if (!cleanupHandledAsync) {
                    try {
                        cursor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    };

    public void copyTempFileToActualFile(Download curDownload) {
        File sourceFile = new File(Uri.parse(curDownload.getTempFilePath()).getPath());
        File destFile = new File(Uri.parse(curDownload.getFilePath()).getPath());
        File destParent = destFile.getParentFile();
        if (destParent != null && !destParent.exists()) {
            destParent.mkdirs();
        }

        if (sourceFile.renameTo(destFile) || copyFile(sourceFile, destFile)) {
            curDownload.getCallbackContextDownloadStart().success();
        } else {
            curDownload.getCallbackContextDownloadStart().error("Cannot copy from temporary path to actual path");
        }
    }

    private boolean copyFile(File sourceFile, File destFile) {
        try (FileInputStream inputStream = new FileInputStream(sourceFile);
             FileOutputStream outputStream = new FileOutputStream(destFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
            return sourceFile.delete();
        } catch (IOException ex) {
            return false;
        }
    }

    public static class HttpDownloadWorker extends Worker {

        public HttpDownloadWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            String uriString = getInputData().getString(WORK_INPUT_URI);
            String tempFilePath = getInputData().getString(WORK_INPUT_TEMP_PATH);
            String notificationTitle = getInputData().getString(WORK_INPUT_TITLE);

            if (TextUtils.isEmpty(uriString) || TextUtils.isEmpty(tempFilePath)) {
                return Result.failure(new Data.Builder()
                        .putString(WORK_OUTPUT_MESSAGE, "Missing download parameters")
                        .build());
            }

            File tempFile = new File(tempFilePath);
            File parent = tempFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            deleteFileIfExists(tempFile);

            try {
                setForegroundAsync(createForegroundInfo(notificationTitle, 0L, -1L));
                return downloadToFile(uriString, tempFile, notificationTitle);
            } catch (Exception ex) {
                deleteFileIfExists(tempFile);
                return Result.failure(new Data.Builder()
                        .putString(WORK_OUTPUT_MESSAGE, ex.getMessage() != null ? ex.getMessage() : "Unexpected download error")
                        .putString(WORK_OUTPUT_EXCEPTION_CLASS, ex.getClass().getName())
                        .build());
            }
        }

        private Result downloadToFile(String uriString, File tempFile, String notificationTitle) {
            String currentUri = uriString;
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            FileOutputStream outputStream = null;

            try {
                for (int redirectCount = 0; redirectCount <= HTTP_MAX_REDIRECTS; redirectCount++) {
                    if (isStopped()) {
                        deleteFileIfExists(tempFile);
                        return Result.failure();
                    }

                    URL url = new URL(currentUri);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(HTTP_READ_TIMEOUT_MS);
                    connection.setRequestMethod("GET");
                    connection.connect();

                    int responseCode = connection.getResponseCode();
                    if (isRedirect(responseCode)) {
                        String location = connection.getHeaderField("Location");
                        connection.disconnect();
                        if (TextUtils.isEmpty(location)) {
                            deleteFileIfExists(tempFile);
                            return Result.failure(new Data.Builder()
                                    .putString(WORK_OUTPUT_MESSAGE, "Redirect response missing Location header")
                                    .putInt(WORK_OUTPUT_HTTP_STATUS, responseCode)
                                    .build());
                        }
                        currentUri = new URL(url, location).toString();
                        continue;
                    }

                    if (responseCode != HttpURLConnection.HTTP_OK
                            && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                        String responseMessage = connection.getResponseMessage();
                        connection.disconnect();
                        deleteFileIfExists(tempFile);
                        return Result.failure(new Data.Builder()
                                .putString(WORK_OUTPUT_MESSAGE, "HTTP download failed")
                                .putInt(WORK_OUTPUT_HTTP_STATUS, responseCode)
                                .putString(WORK_OUTPUT_HTTP_MESSAGE, responseMessage)
                                .putString(WORK_OUTPUT_FINAL_URI, currentUri)
                                .build());
                    }

                    long totalBytes = resolveTotalBytes(connection);
                    setProgressAsync(new Data.Builder()
                            .putLong(WORK_PROGRESS_BYTES_RECEIVED, 0L)
                            .putLong(WORK_PROGRESS_TOTAL_BYTES, totalBytes)
                            .build());
                    setForegroundAsync(createForegroundInfo(notificationTitle, 0L, totalBytes));

                    inputStream = connection.getInputStream();
                    outputStream = new FileOutputStream(tempFile, false);
                    byte[] buffer = new byte[8192];
                    long bytesReceived = 0L;
                    long lastReportedBytes = 0L;
                    long lastReportedAt = System.currentTimeMillis();
                    int bytesRead;

                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        if (isStopped()) {
                            closeQuietly(inputStream);
                            closeQuietly(outputStream);
                            connection.disconnect();
                            deleteFileIfExists(tempFile);
                            return Result.failure();
                        }

                        outputStream.write(buffer, 0, bytesRead);
                        bytesReceived += bytesRead;

                        long now = System.currentTimeMillis();
                        if (bytesReceived - lastReportedBytes >= HTTP_PROGRESS_MIN_BYTES
                                || now - lastReportedAt >= HTTP_PROGRESS_MIN_INTERVAL_MS) {
                            reportProgress(notificationTitle, bytesReceived, totalBytes);
                            lastReportedBytes = bytesReceived;
                            lastReportedAt = now;
                        }
                    }
                    outputStream.flush();
                    reportProgress(notificationTitle, bytesReceived, totalBytes);

                    closeQuietly(inputStream);
                    closeQuietly(outputStream);
                    connection.disconnect();

                    return Result.success(new Data.Builder()
                            .putLong(WORK_PROGRESS_BYTES_RECEIVED, bytesReceived)
                            .putLong(WORK_PROGRESS_TOTAL_BYTES, totalBytes)
                            .putString(WORK_OUTPUT_FINAL_URI, currentUri)
                            .build());
                }

                deleteFileIfExists(tempFile);
                return Result.failure(new Data.Builder()
                        .putString(WORK_OUTPUT_MESSAGE, "Too many redirects")
                        .build());
            } catch (Exception ex) {
                deleteFileIfExists(tempFile);
                return Result.failure(new Data.Builder()
                        .putString(WORK_OUTPUT_MESSAGE, ex.getMessage() != null ? ex.getMessage() : "HTTP download error")
                        .putString(WORK_OUTPUT_EXCEPTION_CLASS, ex.getClass().getName())
                        .putString(WORK_OUTPUT_FINAL_URI, currentUri)
                        .build());
            } finally {
                closeQuietly(inputStream);
                closeQuietly(outputStream);
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        private void reportProgress(String notificationTitle, long bytesReceived, long totalBytes) {
            setProgressAsync(new Data.Builder()
                    .putLong(WORK_PROGRESS_BYTES_RECEIVED, bytesReceived)
                    .putLong(WORK_PROGRESS_TOTAL_BYTES, totalBytes)
                    .build());
            setForegroundAsync(createForegroundInfo(notificationTitle, bytesReceived, totalBytes));
        }

        private ForegroundInfo createForegroundInfo(String notificationTitle, long bytesReceived, long totalBytes) {
            ensureNotificationChannel();

            Context context = getApplicationContext();
            String title = TextUtils.isEmpty(notificationTitle) ? "Downloading file" : notificationTitle;
            NotificationCompat.Builder builder = new NotificationCompat.Builder(context, WORK_NOTIFICATION_CHANNEL_ID)
                    .setSmallIcon(context.getApplicationInfo().icon)
                    .setContentTitle(title)
                    .setContentText(totalBytes > 0 ? bytesReceived + " / " + totalBytes + " bytes" : bytesReceived + " bytes")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW);

            if (totalBytes > 0) {
                builder.setProgress((int) Math.min(Integer.MAX_VALUE, totalBytes), (int) Math.min(Integer.MAX_VALUE, bytesReceived), false);
            } else {
                builder.setProgress(0, 0, true);
            }

            Notification notification = builder.build();
            return new ForegroundInfo(WORK_NOTIFICATION_BASE_ID + Math.abs(getId().hashCode() % 1000), notification);
        }

        private void ensureNotificationChannel() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                return;
            }
            NotificationManager notificationManager = (NotificationManager) getApplicationContext().getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null || notificationManager.getNotificationChannel(WORK_NOTIFICATION_CHANNEL_ID) != null) {
                return;
            }

            NotificationChannel channel = new NotificationChannel(
                    WORK_NOTIFICATION_CHANNEL_ID,
                    WORK_NOTIFICATION_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Fallback background file downloads");
            notificationManager.createNotificationChannel(channel);
        }

        private static boolean isRedirect(int responseCode) {
            return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                    || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                    || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                    || responseCode == 307
                    || responseCode == 308;
        }

        private static long resolveTotalBytes(HttpURLConnection connection) {
            String contentRange = connection.getHeaderField("Content-Range");
            if (!TextUtils.isEmpty(contentRange)) {
                int slashIndex = contentRange.lastIndexOf('/');
                if (slashIndex >= 0 && slashIndex + 1 < contentRange.length()) {
                    try {
                        return Long.parseLong(contentRange.substring(slashIndex + 1));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            long contentLength = connection.getContentLengthLong();
            return contentLength >= 0 ? contentLength : -1L;
        }

        private static void closeQuietly(InputStream inputStream) {
            if (inputStream == null) {
                return;
            }
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }

        private static void closeQuietly(FileOutputStream outputStream) {
            if (outputStream == null) {
                return;
            }
            try {
                outputStream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
