# A log's stack trace is read with the mapping file of its build (app/build/outputs/mapping/builds).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Native code finds these classes, their fields and callbacks by name, and JNA binds Vosk's native methods by reflection.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn java.awt.**

# UsbSerialProber finds each driver's constructor and getSupportedDevices() by reflection.
-keep class com.hoho.android.usbserial.** { *; }

# Tile sources and overlays by name in a library with no rules of its own.
-keep class org.osmdroid.** { *; }
