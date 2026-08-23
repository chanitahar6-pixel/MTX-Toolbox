# Keep JNI entry points and callback interfaces reachable from native code.
-keepclasseswithmembernames class app.mtx.toolbox.core.NativeLib { native <methods>; }
-keep class app.mtx.toolbox.core.NativeLib { *; }
-keep class app.mtx.toolbox.core.Native { *; }
-keep interface app.mtx.toolbox.core.ProgressSink { *; }
-keep interface app.mtx.toolbox.core.RowSink { *; }
-keep class app.mtx.toolbox.core.OpResult { *; }
-keepattributes SourceFile,LineNumberTable
