# TestChéo — R8 rules for release builds (minify enabled).
# Keep readable stack traces in Play Console after deobfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# --- kotlinx.serialization: @Serializable models in data/ (resolved via reflection) ---
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.ntt.crosstest.**$$serializer { *; }
-keepclassmembers class com.ntt.crosstest.** {
    *** Companion;
}
-keepclasseswithmembers class com.ntt.crosstest.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- WorkManager instantiates workers by reflection from stored class names ---
-keep class * extends androidx.work.ListenableWorker {
    public <init>(...);
}

# --- Supabase KT: plugins/serializers resolved reflectively ---
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**

# --- Ktor: engines/content converters registered via service loading ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# OkHttp / Okio ship their own consumer rules; silence leftover warnings.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
