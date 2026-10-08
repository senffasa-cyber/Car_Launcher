# Keep the classes that are only reachable through reflection or from other processes.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Reflection bridges (optional system/vehicle integrations) must survive R8.
-keep class com.arena.carlauncher.vehicle.** { *; }
-keep class com.arena.carlauncher.media.NotificationMediaBridge { *; }
-keep class com.arena.carlauncher.services.** { *; }
-keep class com.arena.carlauncher.receivers.** { *; }

# AppCompat/ViewBinding generated classes are referenced from resources only.
-keep class com.arena.carlauncher.databinding.** { *; }

-dontwarn android.car.**
-dontwarn com.google.android.gms.**
