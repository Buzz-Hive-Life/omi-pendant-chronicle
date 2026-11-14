# Keep the app's own entry points — R8 must not strip what Android instantiates by name.
-keep class com.connor.pendant.** { *; }
# libraries that reflect over their own types (R8 fails a release build on missing optional deps)
-dontwarn kotlinx.coroutines.**
-dontwarn io.ktor.**
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
