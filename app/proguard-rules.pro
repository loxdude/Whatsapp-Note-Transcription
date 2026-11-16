-keep class org.tensorflow.lite.** { *; }


# LiteRT (com.google.ai.edge.litert) is reached via JNI; keep it under minification.
-keep class com.google.ai.edge.litert.** { *; }
