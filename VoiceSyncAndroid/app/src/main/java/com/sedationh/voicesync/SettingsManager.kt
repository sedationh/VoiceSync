package com.sedationh.voicesync

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置管理器
 * 负责应用设置的持久化存储
 */
class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    
    companion object {
        private const val KEY_AUTO_SEND_ENABLED = "auto_send_enabled"
        private const val KEY_AUTO_CLEAR_ENABLED = "auto_clear_enabled"
        private const val KEY_AUTO_ENTER_ENABLED = "auto_enter_enabled"
        
        // 默认值
        private const val DEFAULT_AUTO_SEND = false  // 默认关闭自动发送
        private const val DEFAULT_AUTO_CLEAR = true  // 默认开启自动清空
        private const val DEFAULT_AUTO_ENTER = false // 默认关闭自动回车
    }
    
    /**
     * 获取自动发送开关状态
     */
    fun getAutoSendEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SEND_ENABLED, DEFAULT_AUTO_SEND)
    }
    
    /**
     * 保存自动发送开关状态
     */
    fun setAutoSendEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SEND_ENABLED, enabled).apply()
    }
    
    /**
     * 获取自动清空开关状态
     */
    fun getAutoClearEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CLEAR_ENABLED, DEFAULT_AUTO_CLEAR)
    }
    
    /**
     * 保存自动清空开关状态
     */
    fun setAutoClearEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CLEAR_ENABLED, enabled).apply()
    }
    
    /**
     * 获取自动回车开关状态
     */
    fun getAutoEnterEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_ENTER_ENABLED, DEFAULT_AUTO_ENTER)
    }
    
    /**
     * 保存自动回车开关状态
     */
    fun setAutoEnterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_ENTER_ENABLED, enabled).apply()
    }
}
