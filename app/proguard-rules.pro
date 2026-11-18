# Skyfall's code-shrinking rules (R8, release build only).

# Keep names: crash notes and the crash log stay readable, and nothing found by name is renamed.
-dontobfuscate
# ...and line numbers, so a crash note says where.
-keepattributes SourceFile,LineNumberTable

# The backup player's VLC engine calls into its Java side by name from native code.
-keep class org.videolan.** { *; }
-dontwarn org.videolan.**

# The FFmpeg sound decoder is found by name and called from native code.
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Libraries mention optional extras that aren't included.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
