-keep class org.tensorflow.lite.** { *; }


# LiteRT (com.google.ai.edge.litert) is reached via JNI; keep it under minification.
-keep class com.google.ai.edge.litert.** { *; }

# Exported JNI entry points encode this class and its native method names.
-keep class com.local.voicenotes.audio.NativeOpus { *; }
-keep class com.local.voicenotes.audio.NativeOpusControl { *; }
