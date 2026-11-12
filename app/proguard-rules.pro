# PdfBox-Android: loads classes and resources by name.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# JSch: ciphers, key exchanges and signatures are loaded by class name from its configuration.
-keep class com.jcraft.jsch.** { *; }
# Its optional integrations (Windows Pageant, logging libraries, Kerberos, Unix sockets) are not used.
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**

# ML Kit brings an old WorkManager (2.7) built on Room 2.2. Room creates the database class by reflection,
# and the old library rules do not keep its constructor, so R8 removed it and the release crashed at start.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
# ML Kit finds its components by reflection (registrar classes named in the manifest). R8 removed their
# constructors too, so RemoteModelManager got nothing and handwriting to text crashed.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
