-keep class org.tensorflow.lite.** { *; }


# LiteRT (com.google.ai.edge.litert) is reached via JNI; keep it under minification.
-keep class com.google.ai.edge.litert.** { *; }

# Exported JNI entry points encode this class and its native method names.
-keep class com.local.voicenotes.audio.NativeOpus { *; }
-keep class com.local.voicenotes.audio.NativeOpusControl { *; }

# Cohere JNI entry points and the callback interface native code calls by name.
-keep class com.local.voicenotes.inference.NativeCohere { *; }
-keep interface com.local.voicenotes.inference.CohereControl { *; }

# LiteRT-LM calls back into its Kotlin classes from JNI and reads configs reflectively.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
