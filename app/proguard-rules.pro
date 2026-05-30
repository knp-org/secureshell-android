# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /opt/android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.

# Keep JSch
-keep class com.jcraft.jsch.** { *; }
-keep interface com.jcraft.jsch.** { *; }

# Keep Room
-keep class androidx.room.** { *; }
-keep interface androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase

# Keep entities for serialization
-keepclassmembers class com.secureshell.pro.data.db.entity.** { *; }
