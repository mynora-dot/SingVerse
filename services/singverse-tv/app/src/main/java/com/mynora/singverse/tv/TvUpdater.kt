package com.mynora.singverse.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class TvUpdater(private val context: Context, private val client: TvClient) {
    suspend fun download(release: JSONObject): File = withContext(Dispatchers.IO) {
        require(release.getString("url") == "/api/v1/tv/releases/apk")
        val size=release.getLong("size");require(size in 1..268435456)
        val directory=File(context.cacheDir,"updates").apply { mkdirs() };val file=File(directory,"singverse-tv.apk")
        val connection=URI(client.base+"/api/v1/tv/releases/apk").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout=5000;connection.readTimeout=15000;connection.instanceFollowRedirects=false;connection.setRequestProperty("Authorization","Bearer ${client.token}")
            require(connection.responseCode==200){"无法读取升级包"};val hash=MessageDigest.getInstance("SHA-256");var length=0L
            connection.inputStream.use { input -> file.outputStream().use { output -> val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;length+=n;require(length<=size){"升级包大小异常"};hash.update(bytes,0,n);output.write(bytes,0,n)} } }
            require(length==size && hash.digest().joinToString(""){"%02x".format(it)}==release.getString("sha256")){"升级包校验失败"}
            @Suppress("DEPRECATION")
            val flags=if(Build.VERSION.SDK_INT>=28)PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            @Suppress("DEPRECATION")
            val archive=context.packageManager.getPackageArchiveInfo(file.absolutePath,flags) ?: error("安装包格式无效")
            @Suppress("DEPRECATION")
            val installed=context.packageManager.getPackageInfo(context.packageName,flags)
            require(archive.packageName==context.packageName){"安装包不是 SingVerse TV"}
            val version=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else @Suppress("DEPRECATION") archive.versionCode.toLong()
            val old=if(Build.VERSION.SDK_INT>=28)installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
            require(version==release.getLong("versionCode") && version>old){"安装包版本无效或不是更新版本"}
            @Suppress("DEPRECATION")
            val candidate=if(Build.VERSION.SDK_INT>=28)archive.signingInfo?.apkContentsSigners else archive.signatures
            @Suppress("DEPRECATION")
            val current=if(Build.VERSION.SDK_INT>=28)installed.signingInfo?.apkContentsSigners else installed.signatures
            require(candidate!=null && current!=null && candidate.map { it.toCharsString() }.toSet()==current.map { it.toCharsString() }.toSet()){ "签名与当前应用不一致，拒绝安装" }
            file
        }catch(e: Exception){file.delete();throw e}finally{connection.disconnect()}
    }
    fun install(file: File) {
        if(Build.VERSION.SDK_INT>=26&&!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")));return
        }
        val uri=FileProvider.getUriForFile(context,context.packageName+".updates",file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
