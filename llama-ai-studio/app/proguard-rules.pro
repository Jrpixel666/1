# JNI calls these by exact name/signature with no visible Java/Kotlin call site — R8 would
# otherwise strip or rename them and silently break native loading at runtime.
-keep class com.llamaaistudio.app.engine.LlamaNative { *; }
-keep class com.llamaaistudio.app.engine.LlamaNative$TokenCallback { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

# Moshi codegen: keep the data classes and their generated adapters intact.
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-keepclassmembers class * {
    @com.squareup.moshi.FromJson *;
    @com.squareup.moshi.ToJson *;
}

# Room entities are touched reflectively by generated DAOs at startup.
-keep class com.llamaaistudio.app.data.db.** { *; }

-keep interface com.llamaaistudio.app.data.hf.HfApi { *; }

-dontwarn okhttp3.**
-dontwarn retrofit2.**
-dontwarn kotlinx.coroutines.**
