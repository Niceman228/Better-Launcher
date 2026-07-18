# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Keep data classes
-keep class com.customlauncher.app.data.** { *; }

# Keep service classes
-keep class com.customlauncher.app.service.** { *; }

# Shizuku: UserService is instantiated by reflection in a separate process and
# talks over the AIDL Stub. Without these keeps R8 obfuscates them and the
# UserService fails to start (NPE in LoadedApk.makeApplication) — mobile-data
# toggling in hidden mode silently stops working on release builds.
-keep class com.customlauncher.app.UserService { *; }
-keep class com.customlauncher.app.IUserService { *; }
-keep class com.customlauncher.app.IUserService$* { *; }
-keep interface com.customlauncher.app.IUserService { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }
-dontwarn rikka.shizuku.**
-dontwarn moe.shizuku.**

# Keep broadcast receivers
-keep class com.customlauncher.app.receiver.** { *; }

# Keep activities
-keep class com.customlauncher.app.ui.** extends android.app.Activity { *; }

# Keep custom views
-keep class com.customlauncher.app.ui.widget.** extends android.view.View { *; }
-keep class com.customlauncher.app.ui.layout.** extends android.view.ViewGroup { *; }

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
  public static final android.os.Parcelable$Creator *;
}

# Remove all logs in release builds for better performance and security
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep line numbers for better crash reports (optional)
-keepattributes SourceFile,LineNumberTable

# Keep class names for better crash reports
-keepattributes *Annotation*

# Optimization for better performance
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-optimizationpasses 5
-allowaccessmodification
