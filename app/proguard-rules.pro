# 保留 JS 桥接方法名（Instrumentation 通过方法名查找）
-keepclassmembers class com.gba60hz.app.GbaBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*
