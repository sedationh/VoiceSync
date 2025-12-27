package com.sedationh.voicesync

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateListOf
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * IP 历史记录数据类
 */
data class IpHistory(
    val ipAddress: String,
    val lastUsedTime: Long = System.currentTimeMillis()
)

/**
 * IP 历史管理器
 * 负责 IP 地址的持久化存储、查询和管理
 */
class IpHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("ip_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    
    // 使用 mutableStateListOf 以便 Compose 能够自动感知变化
    private val _historyList = mutableStateListOf<IpHistory>()
    val historyList: List<IpHistory> get() = _historyList
    
    companion object {
        private const val KEY_IP_HISTORY = "ip_history_list"
        private const val MAX_HISTORY_SIZE = 10 // 最多保存 10 条记录
    }
    
    init {
        loadHistory()
    }
    
    /**
     * 从 SharedPreferences 加载历史记录
     */
    private fun loadHistory() {
        val json = prefs.getString(KEY_IP_HISTORY, null) ?: return
        val type = object : TypeToken<List<IpHistory>>() {}.type
        val loadedList = gson.fromJson<List<IpHistory>>(json, type) ?: return
        _historyList.clear()
        _historyList.addAll(loadedList)
    }
    
    /**
     * 保存历史记录到 SharedPreferences
     */
    private fun saveHistory() {
        val json = gson.toJson(_historyList)
        prefs.edit().putString(KEY_IP_HISTORY, json).apply()
    }
    
    /**
     * 添加或更新 IP 地址记录
     * - 如果 IP 已存在，更新其最后使用时间并移到最前面
     * - 如果是新 IP，直接添加到最前面
     * - 超过最大数量时，删除最旧的记录
     */
    fun addOrUpdateIp(ipAddress: String) {
        if (ipAddress.isBlank()) return
        
        // 移除已存在的相同 IP（如果有）
        _historyList.removeAll { it.ipAddress == ipAddress }
        
        // 添加到列表最前面
        _historyList.add(0, IpHistory(ipAddress, System.currentTimeMillis()))
        
        // 限制列表大小
        while (_historyList.size > MAX_HISTORY_SIZE) {
            _historyList.removeAt(_historyList.lastIndex)
        }
        
        saveHistory()
    }
    
    /**
     * 删除指定 IP 地址
     */
    fun deleteIp(ipAddress: String) {
        _historyList.removeAll { it.ipAddress == ipAddress }
        saveHistory()
    }
    
    /**
     * 清空所有历史记录
     */
    fun clearAll() {
        _historyList.clear()
        saveHistory()
    }
    
    /**
     * 获取最近使用的 IP 地址
     */
    fun getLatestIp(): String? {
        return _historyList.firstOrNull()?.ipAddress
    }
}
