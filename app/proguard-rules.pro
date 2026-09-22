# Keep JNI native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Kotlin metadata
-keep class kotlin.Metadata { *; }
