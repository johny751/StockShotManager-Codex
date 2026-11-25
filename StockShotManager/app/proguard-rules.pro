# Keep Room entities and generated code
-keep class com.stockshot.manager.data.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Gson
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Accessibility service
-keep class com.stockshot.manager.service.** { *; }
