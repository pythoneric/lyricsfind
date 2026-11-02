# R8 rules for the release build. AndroidX, Room, WorkManager and kotlinx.serialization bring their own.

# jaudiotagger finds tag and frame classes by name, and has desktop-only code that never runs on Android.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.swing.**
-keep class org.jaudiotagger.** { *; }

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
