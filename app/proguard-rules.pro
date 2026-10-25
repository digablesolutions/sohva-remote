# Keep kotlinx.serialization generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class fi.digable.braviaremote.** {
    *** Companion;
}
-keepclasseswithmembers class fi.digable.braviaremote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (EncryptedSharedPreferences) references compile-time-only Error Prone annotations.
-dontwarn com.google.errorprone.annotations.**
