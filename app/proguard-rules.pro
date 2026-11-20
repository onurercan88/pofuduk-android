# kotlinx.serialization: keep generated serializers of core models (cache/preferences JSON).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.pofuduk.core.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pofuduk.core.**$$serializer { *; }
