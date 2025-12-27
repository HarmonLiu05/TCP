package com.ouc.tcp.test;

/**
 * 接收方窗口元素
 * 继承自 WindowElem，复用父类结构
 * 主要用于标记接收状态（WAIT / BUFFERED）
 * 参考实验报告 P14
 */
public class ReceiverElem extends WindowElem {
    
    /**
     * 构造函数
     */
    public ReceiverElem() {
        super();
        // 初始状态为 WAIT（等待接收）
        this.flag = WAIT;
    }
    
    /**
     * 标记为已缓存
     */
    public void markBuffered() {
        this.flag = BUFFERED;
    }
}
