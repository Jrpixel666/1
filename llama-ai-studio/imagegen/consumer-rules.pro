# JNI calls SdNative by exact name/signature with no visible call site — consumers'
# R8 pass can't see that and would otherwise strip/rename it, breaking native loading.
-keep class com.llamaaistudio.imagegen.SdNative { *; }
-keep class com.llamaaistudio.imagegen.SdNative$ProgressCallback { *; }
