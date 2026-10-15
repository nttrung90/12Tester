# TestChéo — minify is disabled in build.gradle.kts, this file is a placeholder.
# kotlinx.serialization: keep @Serializable models if minify is ever enabled.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.ntt.crosstest.data.** {
    *** *(...);
}
