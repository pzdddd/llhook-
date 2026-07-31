package bxxd.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

private const val TAG = "llhook-HostRewrite"

/**
 * ============================================================================
 *  请求 Host 改写 (HostRewriteHook)
 * ============================================================================
 *  把所有出站请求里 host = social.irisgw.cn 的请求统一改写为 social.blued.cn。
 *
 *  背景: Blued 新版把大量接口迁移到 social.irisgw.cn (服务端返回 en_data bcencode
 *  密文 / role=-1 等), 而旧版 social.blued.cn 返回明文且数据完整。本 hook 在请求出站
 *  前统一改写 host, 让 APP 与旧版明文接口通信。
 *  (NetworkSpoofHook 仅针对 /visited 单接口改写, 本 hook 覆盖【所有】irisgw 请求)
 *
 *  实现: hook okhttp3.Request$Builder.build() —— 这是所有 OkHttp 请求的必经构造点。
 *    build 前检查 builder.url 的 host, 命中 irisgw 时用 HttpUrl.newBuilder().host(blued)
 *    重新构造 HttpUrl 写回 builder.url (只换 host, 保留 path/query/fragment 等不变)。
 *    APP 的 OkHttp 拦截器 rebuild 请求也会再次经过 build(), 故最终出站请求必被改写。
 *
 *  开关 KEY_ENABLED (switch_rewrite_host): 关闭则不改写任何请求。
 * ============================================================================
 */
object HostRewriteHook : BaseHook {

    const val KEY_ENABLED = "switch_rewrite_host"
    private const val FROM_HOST = "social.irisgw.cn"
    private const val TO_HOST = "social.blued.cn"

    // 诊断去重: 每个 path 首次打印一次, 避免刷屏 (全部进 LSPosed 日志)
    private val seenPaths = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    override fun init(lpparam: XC_LoadPackage.LoadPackageParam) {
        val builderClass = try {
            lpparam.classLoader.loadClass("okhttp3.Request\$Builder")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG ❌ okhttp3.Request.Builder 未找到, 跳过: $t")
            return
        }

        XposedBridge.hookAllMethods(builderClass, "build", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (!Config.isFeatureEnabled(KEY_ENABLED)) return
                    val builder = param.thisObject
                    val urlObj = XposedHelpers.getObjectField(builder, "url") ?: return
                    // host() 在 HttpUrl 里已规范化为小写, 直接比较
                    val host = try { XposedHelpers.callMethod(urlObj, "host") as? String } catch (_: Throwable) { null }
                    if (!host.equals(FROM_HOST, ignoreCase = true)) return

                    // 只换 host, 其余 (scheme/port/path/query/fragment/userinfo) 原样保留
                    val newUrl = try {
                        val nb = XposedHelpers.callMethod(urlObj, "newBuilder")
                        XposedHelpers.callMethod(nb, "host", TO_HOST)
                        XposedHelpers.callMethod(nb, "build")
                    } catch (t: Throwable) {
                        // 兜底: 字符串替换后重新解析 (HttpUrl.newBuilder 不存在时)
                        val httpUrlClass = XposedHelpers.findClass("okhttp3.HttpUrl", builderClass.classLoader)
                        XposedHelpers.callStaticMethod(
                            httpUrlClass, "parse",
                            urlObj.toString().replace(FROM_HOST, TO_HOST, ignoreCase = true)
                        ) ?: return
                    }
                    XposedHelpers.setObjectField(builder, "url", newUrl)

                    // 诊断日志 (同 path 只打一次)
                    val path = try { XposedHelpers.callMethod(newUrl, "encodedPath") as? String }
                        catch (_: Throwable) { "?" }
                    if (seenPaths.add(path)) {
                        XposedBridge.log("$TAG ★host改写 ${FROM_HOST}→${TO_HOST}  path=$path")
                    }
                } catch (t: Throwable) {
                    XposedBridge.log("$TAG 改写失败: $t")
                }
            }
        })

        XposedBridge.log("$TAG 已部署: ${FROM_HOST} → ${TO_HOST} (开关 $KEY_ENABLED)")
    }
}
