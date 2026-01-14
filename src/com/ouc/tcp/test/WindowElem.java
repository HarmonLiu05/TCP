package com.ouc.tcp.test;

import com.ouc.tcp.message.TCP_PACKET;

/**
 * 窗口元素基类
 * 用于封装数据包和其状态标记
 * 参考实验报告 P9-10
 */
public class WindowElem {
    // 数据包
    protected TCP_PACKET packet;
    // 状态标记
    protected int flag;
    
    // 状态常量定义
    public static final int NOT_ACKED = 0;    // 发送方：未确认
    public static final int ACKED = 1;        // 发送方：已确认
    public static final int WAIT = 0;         // 接收方：等待接收
    public static final int BUFFERED = 1;     // 接收方：已缓存
    
    /**
     * 构造函数
     */
    public WindowElem() {
        this.packet = null;
        this.flag = WAIT;
    }
    
    /**
     * 获取数据包
     */
    public TCP_PACKET getPacket() {
        return packet;
    }
    
    /**
     * 设置数据包
     */
    public void setPacket(TCP_PACKET packet) {
        this.packet = packet;
    }
    
    /**
     * 获取状态标记
     */
    public int getFlag() {
        return flag;
    }
    
    /**
     * 设置状态标记
     */
    public void setFlag(int flag) {
        this.flag = flag;
    }
    
    /**
     * 检查是否已确认
     */
    public boolean isAcked() {
        return flag == ACKED;
    }
    
    /**
     * 检查是否已缓存
     */
    public boolean isBuffered() {
        return flag == BUFFERED;
    }
    
    /**
     * 重置元素
     */ public void reset() {
        this.packet = null;
        this.flag = WAIT;
    }
}
