package bxxd.hook

import com.github.kyuubiran.ezxhelper.utils.findMethod
import com.github.kyuubiran.ezxhelper.utils.hookBefore
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.net.InetAddress
import java.net.URL

object AdBlockHook : BaseHook {

    // 广告联盟与追踪器域名黑名单字典 (支持模糊匹配)
    private val adKeywords = listOf(
        // === 老牌主流广告联盟 ===
        "pangolin",       // 穿山甲系列
        "pglstatp",       // 穿山甲数据统计
        "gdt.qq",         // 腾讯广点通
        "e.qq.com",       // 腾讯优量汇
        "mobads",         // 百度联盟
        "kuaishou",       // 快手联盟
        "oceanengine",    // 巨量引擎
        "pstatp",         // 字节系通用广告
        "ad.toutiao",     // 头条广告
        
        
       
    )

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // 【绝杀 1：底层 DNS 解析劫持】
            val inetAddressClass = InetAddress::class.java
            inetAddressClass.findMethod { 
                name == "getAllByName" && parameterTypes.size == 1 && parameterTypes[0] == String::class.java 
            }.hookBefore { param ->
                val host = param.args[0] as? String ?: return@hookBefore
                
                val blockAds = Config.isFeatureEnabled("switch_block_ads")

                // 语音交友域名 host.contains("voice.blued.cn") 始终断网
                if ((blockAds && isAdHost(host)) || host.contains("voice.blued.cn")) {
                    // 强制返回本地回环 IP (127.0.0.1)，让它去访问空气
                    val fakeAddress = InetAddress.getByAddress(host, byteArrayOf(127, 0, 0, 1))
                    param.result = arrayOf(fakeAddress)
                }
            }

            // 【绝杀 2：底层 Socket 连接阻断】
            val urlClass = URL::class.java
            urlClass.findMethod { 
                name == "openConnection" && parameterTypes.isEmpty() 
            }.hookBefore { param ->
                val urlObj = param.thisObject as? URL ?: return@hookBefore
                val host = urlObj.host ?: return@hookBefore
                val urlStr = urlObj.toString().lowercase()

                val blockAds = Config.isFeatureEnabled("switch_block_ads")
                // 精准狙击静态广告图片，防误伤 CDN
                val isExactAdPath = urlStr.contains("/obj/static/ad/")

                if ((blockAds && (isAdHost(host) || isExactAdPath)) || host.contains("voice.blued.cn")) {
                    // 抛出连接异常，直接阻断
                    throw java.net.ConnectException("Connection blocked by llhook")
                }
            }

            urlClass.findMethod { 
                name == "openConnection" && parameterTypes.size == 1 && parameterTypes[0] == java.net.Proxy::class.java 
            }.hookBefore { param ->
                val urlObj = param.thisObject as? URL ?: return@hookBefore
                val host = urlObj.host ?: return@hookBefore
                val urlStr = urlObj.toString().lowercase()

                val blockAds = Config.isFeatureEnabled("switch_block_ads")
                val isExactAdPath = urlStr.contains("/obj/static/ad/")

                if ((blockAds && (isAdHost(host) || isExactAdPath)) || host.contains("voice.blued.cn")) {
                    throw java.net.ConnectException("Connection blocked by llhook")
                }
            }

        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun isAdHost(host: String): Boolean {
        val lowerHost = host.lowercase()
        for (keyword in adKeywords) {
            if (lowerHost.contains(keyword)) return true
        }
        return false
    }
}
