# Shrink and optimise, but keep class and method names: the size win comes from dropping unused
# AndroidX code, not from renaming, and readable stack traces matter more in a sideloaded app
# where crash reports arrive as screenshots.
-dontobfuscate
-keep class com.synseria.synema.KeyLayouts { public static void main(java.lang.String[]); }
# WebRTC is called from its native code by name.
-keep class org.webrtc.** { *; }
