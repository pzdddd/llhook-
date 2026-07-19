package bxxd.hook

import de.robv.android.xposed.callbacks.XC_LoadPackage

interface BaseHook {
    fun init(lpparam: XC_LoadPackage.LoadPackageParam)
}
