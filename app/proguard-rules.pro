# Patapps LoRaWAN ProGuard Rules

# Keep USB Serial library
-keep class com.hoho.android.usbserial.** { *; }

# Keep application classes
-keep class net.patapps.lorawan.** { *; }

# Keep Android components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
