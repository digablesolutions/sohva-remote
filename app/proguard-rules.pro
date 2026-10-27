# Keep kotlinx.serialization generated serializers for our models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.mothandgravel.sohva.** {
    *** Companion;
}
-keepclasseswithmembers class com.mothandgravel.sohva.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Tink (EncryptedSharedPreferences) references compile-time-only Error Prone annotations.
-dontwarn com.google.errorprone.annotations.**
