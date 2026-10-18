-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class dev.boottv.**$$serializer { *; }
-keepclassmembers class dev.boottv.** { *** Companion; }
-keepclasseswithmembers class dev.boottv.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
# libtorrent4j calls back into its SWIG classes from native code.
-keep class org.libtorrent4j.** { *; }
