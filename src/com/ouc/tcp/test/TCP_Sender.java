/***************************TCP: 基础TCP协议（无拥塞控制）
**************************** GBN发送端：单一定时器+累积确认+重传所有 */

package com.ouc.tcp.test;

import com.ouc.tcp.client.TCP_Sender_ADT;
import com.ouc.tcp.message.*;

public class TCP_Sender extends TCP_Sender_ADT {
	
	// TCP协议发送窗口：GBN的发送端逻辑
	private SenderWindow senderWindow;
	// 窗口容量：同时允许多少个未确认的包在网络中传输
	private static final int WINDOW_SIZE = 10;
	
	/*构造函数*/
	public TCP_Sender() {
		super();
		super.initTCP_Sender(this);
		// TCP协议初始化：创建发送窗口，使用GBN的单一定时器
		senderWindow = new SenderWindow(WINDOW_SIZE, client, this);
		System.out.println("TCP协议发送端启动 - 窗口大小=" + WINDOW_SIZE + ", GBN发送端逻辑");
	}
	
	@Override
	// TCP协议发送方法（GBN发送端）
	public void rdt_send(int dataIndex, int[] appData) {
		
		// TCP协议流控：窗口满时自旋等待
		while (senderWindow.isFull()) {
			// 窗口满时，处理ACK来释放空间
			waitACK();
			Thread.yield();  // 让出CPU，让ACK处理线程有机会运行
		}
		
		// 获取下一个序列号
		int currentSeq = senderWindow.getNextSeq();
		
		// 封装TCP数据包
		tcpH.setTh_seq(currentSeq);
		tcpS.setData(appData);
		TCP_PACKET tcpPack = new TCP_PACKET(tcpH, tcpS, destinAddr);
		
		// 计算校验和
		tcpH.setTh_sum(CheckSum.computeChkSum(tcpPack));
		tcpPack.setTcpH(tcpH);
		
		// 关键：必须 clone，避免引用被后续修改
		try {
			senderWindow.pushPacket(tcpPack.clone());
		} catch (CloneNotSupportedException e) {
			e.printStackTrace();
		}
		
		// 调用 sendPacket 执行发送
		senderWindow.sendPacket(this);
		
		// TCP协议关键：每次发送后都处理待处理的ACK
		waitACK();
	}
	
	@Override
	// 通过不可靠信道发送数据包
	public void udt_send(TCP_PACKET stcpPack) {
		// TCP协议测试配置：
		// eflag=0 无差错（快速测试）
		// eflag=4 出错/丢包（中等测试）
		// eflag=7 出错/丢包/延迟（完整测试，会很慢）
		tcpH.setTh_eflag((byte)7);
		client.send(stcpPack);
	}
	
	@Override
	// TCP协议 ACK处理
	public void waitACK() {
		// 一次性处理所有堆积的ACK，避免延迟
		while (!ackQueue.isEmpty()) {
			int ackSeq = ackQueue.poll();
			// 调用窗口的 ackPacket 方法
			senderWindow.ackPacket(ackSeq);
		}
	}

	@Override
	// 接收ACK报文：底层框架收到ACK后会回调此方法
	public void recv(TCP_PACKET recvPack) {
		int ackSeq = recvPack.getTcpH().getTh_ack();
		
		System.out.println(">>> 收到ACK - seq=" + ackSeq + " <<<");
		
		// TCP协议关键：直接处理ACK，确保定时器被取消
		// 不能只放入队列，因为rdt_send结束后没人调用waitACK
		senderWindow.ackPacket(ackSeq);
	}
	
}
