/*
 Licensed to the Apache Software Foundation (ASF) under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. The ASF licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License. You may obtain a copy of the License at
 
 http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied. See the License for the
 specific language governing permissions and limitations
 under the License.
 */

#import "BackgroundDownload.h"

static NSString *const kBackgroundDownloadSessionIdentifierSuffix = @"cordova.plugin.BackgroundDownload.BackgroundSession";

@implementation BackgroundDownload {
    NSMutableDictionary<NSNumber *, NSString *> *_callbackIdsByTaskId;
    NSMutableDictionary<NSNumber *, NSString *> *_downloadUrisByTaskId;
    NSMutableDictionary<NSNumber *, NSString *> *_targetFilesByTaskId;
    NSMutableDictionary<NSNumber *, NSError *> *_writeErrorsByTaskId;
    NSMutableSet<NSNumber *> *_ignoreCompletionForTaskIds;
}

@synthesize session;
@synthesize downloadTask;

- (NSString *)backgroundSessionIdentifier
{
    NSString *bundleIdentifier = [[NSBundle mainBundle] bundleIdentifier];
    if (bundleIdentifier.length == 0) {
        bundleIdentifier = @"com.cordova.plugin.BackgroundDownload";
    }

    return [NSString stringWithFormat:@"%@.%@", bundleIdentifier, kBackgroundDownloadSessionIdentifierSuffix];
}

- (void)pluginInitialize
{
    [super pluginInitialize];
    _callbackIdsByTaskId = [NSMutableDictionary dictionary];
    _downloadUrisByTaskId = [NSMutableDictionary dictionary];
    _targetFilesByTaskId = [NSMutableDictionary dictionary];
    _writeErrorsByTaskId = [NSMutableDictionary dictionary];
    _ignoreCompletionForTaskIds = [NSMutableSet set];
    self.session = [self backgroundSession];
}

- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    NSString *downloadUri = [command.arguments objectAtIndex:0];
    NSString *targetFile = [command.arguments objectAtIndex:1];
    
    NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:downloadUri]];
    
    self.session = [self backgroundSession];
    [self.session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
        NSURLSessionDownloadTask *matchingTask = nil;
        for (NSURLSessionDownloadTask *existingTask in downloadTasks) {
            NSNumber *taskId = @(existingTask.taskIdentifier);
            if (_callbackIdsByTaskId[taskId] != nil) {
                continue;
            }

            NSString *taskDescription = existingTask.taskDescription;
            NSString *taskURL = existingTask.originalRequest.URL.absoluteString;
            if ((taskDescription != nil && [taskDescription isEqualToString:downloadUri]) ||
                (taskURL != nil && [taskURL isEqualToString:downloadUri])) {
                matchingTask = existingTask;
                break;
            }
        }

        if (matchingTask != nil) {
            self.downloadTask = matchingTask;
        } else {
            self.downloadTask = [self.session downloadTaskWithRequest:request];
            self.downloadTask.taskDescription = downloadUri;
        }

        NSNumber *taskId = @(self.downloadTask.taskIdentifier);
        _callbackIdsByTaskId[taskId] = command.callbackId;
        _downloadUrisByTaskId[taskId] = downloadUri;
        _targetFilesByTaskId[taskId] = targetFile;
        [_ignoreCompletionForTaskIds removeObject:taskId];

        [self.downloadTask resume];
    }];
    
}

- (NSURLSession *)backgroundSession
{
    static NSURLSession *backgroundSession = nil;
    @synchronized([BackgroundDownload class]) {
        if (backgroundSession == nil || backgroundSession.delegate != self) {
            NSURLSessionConfiguration *config = [NSURLSessionConfiguration backgroundSessionConfigurationWithIdentifier:[self backgroundSessionIdentifier]];
            if ([config respondsToSelector:@selector(setSessionSendsLaunchEvents:)]) {
                config.sessionSendsLaunchEvents = YES;
            }
            config.HTTPMaximumConnectionsPerHost = 1;
            backgroundSession = [NSURLSession sessionWithConfiguration:config delegate:self delegateQueue:nil];
        }
    }

    return backgroundSession;
}

- (void)handleEventsForBackgroundURLSession:(NSString *)identifier completionHandler:(void (^)(void))completionHandler
{
    if ([[self backgroundSessionIdentifier] isEqualToString:identifier]) {
        self.backgroundCompletionHandler = completionHandler;
        self.session = [self backgroundSession];
    } else if (completionHandler != nil) {
        completionHandler();
    }
}

- (void)URLSessionDidFinishEventsForBackgroundURLSession:(NSURLSession *)session
{
    void (^completionHandler)(void) = self.backgroundCompletionHandler;
    if (completionHandler == nil) {
        return;
    }

    self.backgroundCompletionHandler = nil;
    dispatch_async(dispatch_get_main_queue(), ^{
        completionHandler();
    });
}

- (void)stop:(CDVInvokedUrlCommand*)command
{
    CDVPluginResult* pluginResult = nil;
    NSString* downloadUri = [command.arguments objectAtIndex:0];
    
    if (downloadUri != nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Arg was null"];
    }

    if (downloadUri != nil) {
        [self.session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
            for (NSURLSessionDownloadTask *existingTask in downloadTasks) {
                NSString *taskDescription = existingTask.taskDescription;
                NSString *taskURL = existingTask.originalRequest.URL.absoluteString;
                if ((taskDescription != nil && [taskDescription isEqualToString:downloadUri]) ||
                    (taskURL != nil && [taskURL isEqualToString:downloadUri])) {
                    [existingTask cancel];
                }
            }
        }];
    }
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didWriteData:(int64_t)bytesWritten totalBytesWritten:(int64_t)totalBytesWritten totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite {
    NSString *callbackId = _callbackIdsByTaskId[@(downloadTask.taskIdentifier)];
    if (callbackId == nil) {
        return;
    }
    
    NSMutableDictionary* progressObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesWritten] forKey:@"bytesReceived"];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesExpectedToWrite] forKey:@"totalBytesToReceive"];
    NSMutableDictionary* resObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [resObj setObject:progressObj forKey:@"progress"];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resObj];
    result.keepCallback = [NSNumber numberWithInteger: TRUE];
    [self.commandDelegate sendPluginResult:result callbackId:callbackId];
}

-(void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    NSNumber *taskId = @(task.taskIdentifier);
    NSString *callbackId = _callbackIdsByTaskId[taskId];
    NSString *downloadUri = _downloadUrisByTaskId[taskId];

    if ([_ignoreCompletionForTaskIds containsObject:taskId]) {
        [_ignoreCompletionForTaskIds removeObject:taskId];
        return;
    }
    
    if (error != nil) {
        if ((error.code == -999)) {
            NSData* resumeData = [[error userInfo] objectForKey:NSURLSessionDownloadTaskResumeData];
            // resumeData is available only if operation was terminated by the system (no connection or other reason)
            // this happens when application is closed when there is pending download, so we try to resume it
            if (resumeData != nil && callbackId != nil && downloadUri != nil) {
                [_ignoreCompletionForTaskIds addObject:taskId];
                [(NSURLSessionDownloadTask *)task cancel];
                self.downloadTask = [self.session downloadTaskWithResumeData:resumeData];
                self.downloadTask.taskDescription = downloadUri;
                NSNumber *replacementTaskId = @(self.downloadTask.taskIdentifier);
                _callbackIdsByTaskId[replacementTaskId] = callbackId;
                _downloadUrisByTaskId[replacementTaskId] = downloadUri;
                if (_targetFilesByTaskId[taskId] != nil) {
                    _targetFilesByTaskId[replacementTaskId] = _targetFilesByTaskId[taskId];
                }
                [_callbackIdsByTaskId removeObjectForKey:taskId];
                [_downloadUrisByTaskId removeObjectForKey:taskId];
                [_targetFilesByTaskId removeObjectForKey:taskId];
                [self.downloadTask resume];
                return;
            }
        }
        if (callbackId != nil) {
            CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]];
            [self.commandDelegate sendPluginResult:errorResult callbackId:callbackId];
        }
    } else if (_writeErrorsByTaskId[taskId] != nil) {
        NSError *writeError = _writeErrorsByTaskId[taskId];
        if (callbackId != nil) {
            CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[writeError localizedDescription]];
            [self.commandDelegate sendPluginResult:errorResult callbackId:callbackId];
        }
    } else {
        if (callbackId != nil) {
            CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:callbackId];
        }
    }

    [_callbackIdsByTaskId removeObjectForKey:taskId];
    [_downloadUrisByTaskId removeObjectForKey:taskId];
    [_targetFilesByTaskId removeObjectForKey:taskId];
    [_writeErrorsByTaskId removeObjectForKey:taskId];
    [_ignoreCompletionForTaskIds removeObject:taskId];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    
    NSString *targetFile = _targetFilesByTaskId[@(downloadTask.taskIdentifier)];
    if (targetFile == nil) {
        return;
    }

    NSURL *targetURL = [NSURL URLWithString:targetFile];
    if (targetURL == nil || !targetURL.isFileURL) {
        NSError *error = [NSError errorWithDomain:@"BackgroundDownload"
                                             code:1
                                         userInfo:@{NSLocalizedDescriptionKey: [NSString stringWithFormat:@"Background download target is not a file URL: %@", targetFile]}];
        _writeErrorsByTaskId[@(downloadTask.taskIdentifier)] = error;
        NSLog(@"[BackgroundDownload] %@", error.localizedDescription);
        return;
    }

    NSError *removeError = nil;
    [fileManager removeItemAtURL:targetURL error:&removeError];
    if (removeError != nil && removeError.code != NSFileNoSuchFileError) {
        _writeErrorsByTaskId[@(downloadTask.taskIdentifier)] = removeError;
        NSLog(@"[BackgroundDownload] Could not remove %@: %@", targetURL.path, removeError.localizedDescription);
        return;
    }

    NSError *copyError = nil;
    BOOL didCopy = [fileManager copyItemAtURL:location toURL:targetURL error:&copyError];
    unsigned long long downloadedBytes = [[fileManager attributesOfItemAtPath:location.path error:nil][NSFileSize] unsignedLongLongValue];
    unsigned long long targetBytes = [[fileManager attributesOfItemAtPath:targetURL.path error:nil][NSFileSize] unsignedLongLongValue];
    if (!didCopy || downloadedBytes == 0 || targetBytes != downloadedBytes) {
        NSString *message = [NSString stringWithFormat:@"Could not write background download to %@ (downloaded=%llu bytes, target=%llu bytes)%@",
                             targetURL.path,
                             downloadedBytes,
                             targetBytes,
                             copyError == nil ? @"" : [NSString stringWithFormat:@": %@", copyError.localizedDescription]];
        NSError *error = [NSError errorWithDomain:@"BackgroundDownload"
                                             code:2
                                         userInfo:@{NSLocalizedDescriptionKey: message}];
        _writeErrorsByTaskId[@(downloadTask.taskIdentifier)] = error;
        NSLog(@"[BackgroundDownload] %@", message);
        return;
    }

    NSLog(@"[BackgroundDownload] Wrote %llu bytes to %@", targetBytes, targetURL.path);
}
@end
