# Release: remove chamadas de log de depuracao (argumentos tambem sao descartados).
# Exige proguard-android-optimize.txt (otimizacao ligada). Log.w/Log.e permanecem de proposito.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
