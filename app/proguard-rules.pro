# Names kept: crash reports and logs stay readable. R8 still optimises and drops unused code.
-dontobfuscate

# YouTube trailers: NewPipeExtractor and the JavaScript engine it runs YouTube's player code in use reflection
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class org.jsoup.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-dontwarn java.beans.**
-dontwarn javax.annotation.**
-dontwarn org.jspecify.**
-dontwarn com.google.re2j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# NanoHTTPD (the phone page and Home Assistant's notifications)
-keep class fi.iki.elonen.** { *; }

# Classes Android starts by name (the manifest's are kept already; this keeps the app's own entry points safe)
-keep class io.github.bornaradusin.launcher.LauncherApp { *; }
