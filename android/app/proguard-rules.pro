# Release: remove chamadas de log de depuracao (argumentos tambem sao descartados).
# Exige proguard-android-optimize.txt (otimizacao ligada). Log.w/Log.e permanecem de proposito.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Stack traces legiveis no Play Console (o mapping.txt do AAB desofusca; sem ele o arquivo some do trace).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
