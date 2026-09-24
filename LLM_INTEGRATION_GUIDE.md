# Integration guide for LLMs/agents using this plugin

This file is for an LLM (or a developer) wiring `cordova-plugin-background-download` into a
Cordova/Ionic app. It documents this fork's actual behavior, not the upstream plugin's -- this
fork has diverged significantly (concurrent-download support, an Android `WorkManager` HTTP
fallback, richer failure diagnostics) and some of that behavior is easy to get wrong if you
only read the JS API surface.

## API surface (unchanged)

```js
var downloader = new BackgroundTransfer.BackgroundDownloader();
var download = downloader.createDownload(remoteFileUrl, targetFileEntry, notificationTitle);

download.startAsync().then(
  function onSuccess() { /* download finished, file is at targetFileEntry's path */ },
  function onError(err) { /* string (iOS) or JSON diagnostics object (Android) */ },
  function onProgress(progress) { /* { bytesReceived, totalBytesToReceive } */ }
);

// later, to cancel:
download.stop();
```

`targetFileEntry` is a `FileEntry` (or an object with `.toURL()`/`.nativeURL`) obtained from
`cordova-plugin-file`, which this plugin depends on.

## Choosing the target directory (read this before wiring a new caller)

**Android's `DownloadManager` will only write to app-specific *external* storage.** Passing a
path under the app's *internal* storage (`context.getFilesDir()` -- what
`cordova.file.dataDirectory` / `LocalFileSystem.PERSISTENT`'s root resolve to on Android)
causes `DownloadManager.Request#setDestinationUri()` to fail inside its `ContentProvider` with
`SecurityException: Unsupported path ...`, thrown from a *different process*
(`android.process.media`) and surfaced back to your `startAsync()` call as a plain error. There
is no native-side log for this specific failure path today (see Known limitations).

Use `cordova.file.externalCacheDirectory` (or `cordova.file.externalDataDirectory`, if you want
files to survive a "clear cache") on Android. On iOS there is no such restriction -- the native
side just writes to whatever sandbox path it's given, so `cordova.file.cacheDirectory` (or
`dataDirectory`, or `LocalFileSystem.PERSISTENT`'s Documents root) all work fine.

```js
function getDownloadDirectory() {
  var isAndroid = cordova.platformId === 'android';
  var url = isAndroid ? cordova.file.externalCacheDirectory : cordova.file.cacheDirectory;
  return new Promise(function (resolve, reject) {
    window.resolveLocalFileSystemURL(url, resolve, reject);
  });
}
```

This mirrors what this app's own `tasks-sync.service.ts` already does in its
`_cacheDirectoryForDownloads` getter -- match that pattern for any new caller.

## Concurrency

This fork supports genuinely concurrent downloads of different URIs on **both** platforms, up
to a small hardcoded cap:

- iOS: `kBackgroundDownloadMaxConcurrentDownloads` in `src/ios/BackgroundDownload.m` (currently `3`).
- Android: `MAX_CONCURRENT_DOWNLOADS` in `src/android/BackgroundDownload.java` (currently `3`).

**Requests beyond the cap are silently ignored -- no success or error callback ever fires for
them.** This is intentional (it mirrors the plugin's pre-existing behavior for a duplicate
in-flight URL) but it means a caller that fires off more than the cap at once will see some of
its `startAsync()` promises simply never settle. If you need more than the cap in flight, queue
the extras yourself and only call `startAsync()` for up to `cap` at a time, advancing the queue
as each one's promise settles.

**Starting the same URI twice while it's already in flight is also a silent no-op** -- the second
call's promise never settles either (Android: `activDownloads.containsKey(uri)` early-returns;
iOS: attaches to the existing task without creating a second callback, which loses the *first*
caller's callback slot on iOS specifically -- don't rely on being able to call `startAsync()`
twice for the same URI and get two independent results).

`stop()` is fire-and-forget: it does not settle the `startAsync()` promise it's cancelling. If
your caller needs to know when a stopped download's promise has settled, implement your own
timeout around it (this app's `DownloadService` already does this, with a 10s watchdog, for
exactly this reason).

## Known limitations / non-guarantees

- **A progress callback can very rarely fire once more after the download has already
  succeeded or failed** (Android: a `Timer` tick that was already dispatched when the download
  completed can race past cleanup; harmless, ignore any `onProgress` call after your `onSuccess`
  or `onError` has already run).
- **Android: starting multiple downloads at the exact same moment from a completely idle state
  (zero downloads in flight) has a known race** in `startAsync`'s lazy `BroadcastReceiver`
  registration (`activDownloads.size() == 0` check-then-register is not atomic across the
  thread-pool threads each `startAsync` call runs on). In the unlucky case, one of the
  simultaneous calls fails with a "Receiver already registered" error instead of starting. This
  is pre-existing in the fork; check the file's own comments/CHANGELOG for whether it's been
  fixed by the time you're reading this.
- Progress/size reporting depends on the server sending a real `Content-Length` /
  `Content-Range`; without one you'll get start/success/failure but no percentage in between.
- None of this plugin's error paths carry a stable machine-readable error code on iOS (it's a
  free-text `[error localizedDescription]`); Android's `error()` payload is a structured
  `JSONObject` with much richer diagnostics (status, reason, HTTP probe results, filesystem
  state) -- don't assume the two platforms' error shapes match.

## Where to tune things

Both the concurrency cap and (iOS only) `HTTPMaximumConnectionsPerHost` are controlled by the
single constant named above on each platform -- raise/lower both together if you change the cap,
since `HTTPMaximumConnectionsPerHost` is set equal to the cap so same-host downloads can actually
run in parallel instead of queueing at the connection level.
