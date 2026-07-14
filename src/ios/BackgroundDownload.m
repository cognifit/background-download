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
    bool ignoreNextError;
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
    self.session = [self backgroundSession];
}

- (void)startAsync:(CDVInvokedUrlCommand*)command
{
    self.downloadUri = [command.arguments objectAtIndex:0];
    self.targetFile = [command.arguments objectAtIndex:1];
    
    self.callbackId = command.callbackId;
    
    NSURLRequest *request = [NSURLRequest requestWithURL:[NSURL URLWithString:self.downloadUri]];
    
    ignoreNextError = NO;
    
    self.session = [self backgroundSession];
    [self.session getTasksWithCompletionHandler:^(NSArray *dataTasks, NSArray *uploadTasks, NSArray *downloadTasks) {
        NSURLSessionDownloadTask *matchingTask = nil;
        for (NSURLSessionDownloadTask *existingTask in downloadTasks) {
            NSString *taskDescription = existingTask.taskDescription;
            NSString *taskURL = existingTask.originalRequest.URL.absoluteString;
            if ((taskDescription != nil && [taskDescription isEqualToString:self.downloadUri]) ||
                (taskURL != nil && [taskURL isEqualToString:self.downloadUri])) {
                matchingTask = existingTask;
                break;
            }
        }

        if (matchingTask != nil) {
            self.downloadTask = matchingTask;
        } else {
            self.downloadTask = [self.session downloadTaskWithRequest:request];
            self.downloadTask.taskDescription = self.downloadUri;
        }
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
    NSString* myarg = [command.arguments objectAtIndex:0];
    
    if (myarg != nil) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:@"Arg was null"];
    }
    
    [downloadTask cancel];
    
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didWriteData:(int64_t)bytesWritten totalBytesWritten:(int64_t)totalBytesWritten totalBytesExpectedToWrite:(int64_t)totalBytesExpectedToWrite {
    
    NSMutableDictionary* progressObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesWritten] forKey:@"bytesReceived"];
    [progressObj setObject:[NSNumber numberWithInteger:totalBytesExpectedToWrite] forKey:@"totalBytesToReceive"];
    NSMutableDictionary* resObj = [NSMutableDictionary dictionaryWithCapacity:1];
    [resObj setObject:progressObj forKey:@"progress"];
    CDVPluginResult* result = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:resObj];
    result.keepCallback = [NSNumber numberWithInteger: TRUE];
    [self.commandDelegate sendPluginResult:result callbackId:self.callbackId];
}

-(void)URLSession:(NSURLSession *)session task:(NSURLSessionTask *)task didCompleteWithError:(NSError *)error {
    if (ignoreNextError) {
        ignoreNextError = NO;
        return;
    }
    
    if (error != nil) {
        if ((error.code == -999)) {
            NSData* resumeData = [[error userInfo] objectForKey:NSURLSessionDownloadTaskResumeData];
            // resumeData is available only if operation was terminated by the system (no connection or other reason)
            // this happens when application is closed when there is pending download, so we try to resume it
            if (resumeData != nil) {
                ignoreNextError = YES;
                [downloadTask cancel];
                self.downloadTask = [self.session downloadTaskWithResumeData:resumeData];
                self.downloadTask.taskDescription = self.downloadUri;
                [self.downloadTask resume];
                return;
            }
        }
        CDVPluginResult* errorResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:[error localizedDescription]];
        [self.commandDelegate sendPluginResult:errorResult callbackId:self.callbackId];
    } else {
        CDVPluginResult* pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:self.callbackId];
    }
}

- (void)URLSession:(NSURLSession *)session downloadTask:(NSURLSessionDownloadTask *)downloadTask didFinishDownloadingToURL:(NSURL *)location {
    NSFileManager *fileManager = [NSFileManager defaultManager];
    
    NSURL *targetURL = [NSURL URLWithString:self.targetFile];
    
    [fileManager removeItemAtPath:targetURL.path error: nil];
    [fileManager createFileAtPath:targetURL.path contents:[fileManager contentsAtPath:[location path]] attributes:nil];
}
@end
