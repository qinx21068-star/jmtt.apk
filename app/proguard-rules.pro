# =============================================================================
# v27.5 稳定性加固：用户反馈 release 包多人闪退，深度排查发现大量 keep 规则缺失。
# R8 full mode（AGP 8.7.3 默认开启）下，反射调用的类/方法/字段名必须显式 keep。
# 本文件按"问题分类"组织，每段说明该段 keep 的根因。
# =============================================================================

# -----------------------------------------------------------------------------
# 1. Moshi：JSON 反序列化
# -----------------------------------------------------------------------------
# 项目用 moshi-kotlin（反射模式），KotlinJsonAdapterFactory 通过反射读取
# 构造函数参数名 + @Metadata 注解。R8 改名后会导致 JSON 字段映射错位。
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class com.jmreader.data.dto.** { *; }
-keep class com.jmreader.data.api.saucenao.** { *; }

# v27.5 修复（critical）：HistoryEntry / BrowseEntry 未标 @JsonClass 但通过
# moshi.adapter<List<HistoryEntry>>(type) 反射序列化。R8 改名会让阅读进度 /
# 浏览历史静默丢失（用户反馈"App 啥都没记"）。
-keep class com.jmreader.data.local.HistoryEntry { *; }
-keep class com.jmreader.data.local.BrowseEntry { *; }

# v27.5 修复：FavoriteFolder / FavoriteEntry / FavoriteStoreData 路径修正。
# 之前的规则误写成 com.jmreader.data.local.FavoriteFolder（不存在，死规则）。
# 实际定义在 com.jmreader.data.dto 包（已标 @JsonClass，被上面通用规则覆盖）。
# 此处显式再 keep 一次作为冗余保险，防通用规则被误删导致 Moshi 反射失败、
# favorites.json 反序列化返回空列表（用户感受为"收藏全没了"）。
-keep class com.jmreader.data.dto.FavoriteFolder { *; }
-keep class com.jmreader.data.dto.FavoriteEntry { *; }
-keep class com.jmreader.data.dto.FavoriteStoreData { *; }

# Moshi 注解处理
-keepclassmembers class * {
    @com.squareup.moshi.* <methods>;
}
-keep @com.squareup.moshi.JsonQualifier @interface *
-keep class kotlin.Metadata { *; }

# v27.5 修复：moshi-kotlin 反射读取 @Metadata 注解的构造函数参数名。
# 仅 keep 类本身不够，需要保留构造函数参数与字段名。
-keepclassmembers @kotlin.Metadata class * {
    <init>(...);
    <fields>;
}

# -----------------------------------------------------------------------------
# 2. WebView @JavascriptInterface（critical）
# -----------------------------------------------------------------------------
# 任何 @JavascriptInterface 暴露给 JS 的方法，R8 改名后 JS 找不到 → 调用失败。
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# WebViewClient / WebChromeClient 匿名子类（保险，避免回调被裁剪）
-keepclassmembers class com.jmreader.** {
    public void onPageFinished(android.webkit.WebView, java.lang.String);
    public boolean shouldOverrideUrlLoading(android.webkit.WebView, android.webkit.WebResourceRequest);
    public void onReceivedError(android.webkit.WebView, android.webkit.WebResourceRequest, android.webkit.WebResourceError);
    public void onPageStarted(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean onShowFileChooser(android.webkit.WebView, android.webkit.ValueCallback, android.webkit.WebChromeClient$FileChooserParams);
    public void onProgressChanged(android.webkit.WebView, int);
}

# -----------------------------------------------------------------------------
# 3. Coil 自定义 Fetcher（high）
# -----------------------------------------------------------------------------
# JmImageFetcher 通过 Fetcher.Factory<Uri> 接口按数据类型分发，运行时泛型匹配。
# R8 full mode 可能裁剪 Factory.create() 或内联删除返回 null 的路径，
# 导致禁漫图片永远走未解密路径（错位/花屏）甚至 NPE。
-keep class com.jmreader.data.api.direct.JmImageFetcher { *; }
-keep class com.jmreader.data.api.direct.JmImageFetcher$Factory { *; }

# 禁漫图片解密 / API 加密工具类
-keep class com.jmreader.data.api.direct.JmImageDecoder { *; }
-keep class com.jmreader.data.api.direct.JmImageDecoder$* { *; }
-keep class com.jmreader.data.api.direct.JMCrypto { *; }
-keep class com.jmreader.data.api.direct.JMCrypto$* { *; }
-keep class com.jmreader.data.api.direct.JmDirectClient { *; }
-keep class com.jmreader.data.api.direct.JmDirectClient$* { *; }

# Coil 自定义 ImageLoaderFactory 入口（JMApp 实现 ImageLoaderFactory）
-keep class com.jmreader.core.CoilSetup { *; }

# -----------------------------------------------------------------------------
# 4. ViewModel + Factory（medium）
# -----------------------------------------------------------------------------
# ViewModelProvider 通过反射 + canonical name 复用 VM。R8 改名后配置变化时
# VM 状态丢失（阅读进度丢失/列表重置）。
-keep class com.jmreader.ui.viewmodel.** { *; }
-keep class com.jmreader.ui.screen.**ViewModel { *; }
-keep class com.jmreader.ui.screen.**ViewModel$* { *; }
-keep class com.jmreader.ui.screen.**VMFactory { *; }
-keep class com.jmreader.ui.screen.**VMFactory$* { *; }

# -----------------------------------------------------------------------------
# 5. BiometricPrompt 回调（medium）
# -----------------------------------------------------------------------------
# 应用锁 BiometricPrompt.AuthenticationCallback 匿名子类，验证成功 / 失败回调
# 必须保留方法名（虚方法分派通常安全，但 androidx.biometric 1.1.0 内部反射较多）。
-keep class androidx.biometric.** { *; }
-keepclassmembers class * extends androidx.biometric.BiometricPrompt$AuthenticationCallback {
    public *;
}

# -----------------------------------------------------------------------------
# 6. 反射入口（Application / Activity 由 Manifest 保留，无需额外规则）
# -----------------------------------------------------------------------------
-keep class com.jmreader.JMApp { *; }
-keep class com.jmreader.MainActivity { *; }

# AppContainer 作为依赖容器，被 ViewModel 通过 container.xxx 直接访问。
# 字段访问 R8 通常不会改名，但保险起见保留公共字段与方法。
-keep class com.jmreader.data.AppContainer { *; }

# CrashHandler 设置为 Thread.setDefaultUncaughtExceptionHandler，
# 必须保留默认构造与 uncaughtException 方法。
-keep class com.jmreader.core.CrashHandler { *; }
-keep class com.jmreader.core.CrashHandler$* { *; }
-keep class com.jmreader.core.Logger { *; }

# -----------------------------------------------------------------------------
# 7. Retrofit / OkHttp（high）
# -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**

# Retrofit 接口方法签名 + 参数注解（@Query / @Body / @Path 等）
# consumer rules 通常已覆盖，但 full mode 下额外保险
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# -----------------------------------------------------------------------------
# 8. 关键属性（critical）
# -----------------------------------------------------------------------------
# v27.5 修复：原仅保留 4 个属性。Retrofit @Query 参数注解依赖
# RuntimeVisibleParameterAnnotations；Moshi 泛型签名依赖 Signature + TypeAnnotations；
# 局部类 / 匿名类反射依赖 InnerClasses + EnclosingMethod。
-keepattributes Signature, Exceptions, RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations, RuntimeVisibleParameterAnnotations, RuntimeInvisibleParameterAnnotations, RuntimeParameterAnnotations, AnnotationDefault, InnerClasses, EnclosingMethod, TypeAnnotations, SourceFile, LineNumberTable

# 保留源文件名 + 行号，让崩溃栈可读（即使混淆也能定位到行）
-renamesourcefileattribute SourceFile
-keepattributes SourceFile, LineNumberTable

# -----------------------------------------------------------------------------
# 9. Enum 类（high）
# -----------------------------------------------------------------------------
# v27.5 修复：SettingsStore 大量用 ThemeMode.valueOf(name) / ListStyle.valueOf(name)
# 等从持久化字符串还原枚举。默认 proguard-android-optimize.txt 只保留 valueOf/values
# 方法签名，不显式保留枚举常量字段名。R8 full mode 升级或 AGP 默认规则变更时
# 可能抛 IllegalArgumentException 导致整个设置项回到默认值（用户感受为"所有设置都丢了"）。
# 显式 keep com.jmreader 包下所有枚举的成员，确保 valueOf(name) 调用链稳定。
-keepclassmembers class com.jmreader.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
-keep class com.jmreader.** extends java.lang.Enum { *; }

# -----------------------------------------------------------------------------
# 10. sealed class 子类 + object 单例（medium）
# -----------------------------------------------------------------------------
# v27.5 修复：R8 full mode 偶尔激进优化 sealed class 层级。为防将来引入基于类名
# 的反射多态，显式 keep 关键 sealed class 的子类与 object 单例。
-keep class com.jmreader.data.repository.Resource$* { *; }
-keep class com.jmreader.core.Logger { *; }
-keep class com.jmreader.core.LoggingInterceptor { *; }
-keep class com.jmreader.data.api.NetworkFactory { *; }

# -----------------------------------------------------------------------------
# 11. WebViewClient / WebChromeClient 全部 public 方法（medium）
# -----------------------------------------------------------------------------
# 原规则只覆盖 6 个固定签名。未来添加 onReceivedSslError / shouldInterceptRequest /
# onRenderProcessGone 等回调时不会被保留。改为通配，覆盖所有 public 方法。
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}
