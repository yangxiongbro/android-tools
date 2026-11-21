package com.android_tools.app

import android.app.Application

/**
 * Application entry point.
 *
 * The notification channel is created lazily by [com.android_tools.app.capture.ScreenRecorderService]
 * so it always exists before the foreground notification is posted.
 */
class ScreenRecorderApp : Application()
