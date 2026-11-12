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
