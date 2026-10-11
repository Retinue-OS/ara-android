# kotlinx.serialization keeps generated serializers via annotations; keep them.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class os.retinue.ara.** {
    *** Companion;
}
-keepclasseswithmembers class os.retinue.ara.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp platform classes referenced reflectively; standard suppression.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# The WebView bridge fallback (addJavascriptInterface) is called by name from JS.
-keepclassmembers class os.retinue.ara.web.** {
    @android.webkit.JavascriptInterface <methods>;
}
