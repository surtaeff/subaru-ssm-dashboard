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

# NewPipe Extractor (YouTube): Rhino runs YouTube's player JavaScript and finds its classes by reflection; the extractor
# reads JSON into its own classes and protobuf-lite reads its messages' fields by name.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn org.jspecify.annotations.**
-dontwarn javax.annotation.**
-dontwarn com.google.re2j.**
