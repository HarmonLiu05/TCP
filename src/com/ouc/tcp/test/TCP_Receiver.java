/***************************SR: 选择重传协议*****************/
/***** 参考实验报告 P15-16 ******************************/
/***** 使用数组实现的循环队列 ******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPack;	// ACK报文
	// SR协议接收窗口：用数组存储乱序到达的数据包
	private ReceiverWindow receiverWindow;
	// 窗口大小：与发送方保持一致
	private static final int WINDOW_SIZE = 10;
	
	/*构造函数*/
	public TCP_Receiver() {
		super();
		super.initTCP_Receiver(this);
		// SR协议初始化：创建接收窗口，从序列号0开始接收
		receiverWindow = new ReceiverWindow(WINDOW_SIZE, 0);
		System.out.println("SR协议接收端启动 - 窗口大小=" + WINDOW_SIZE);
	}

	@Override
	// SR协议接收方法：参考实验报告 P15-16
	public void rdt_recv(TCP_PACKET recvPack) {
		// 步骤1：校验数据完整性
		if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			// 数据没有损坏
			int seq = recvPack.getTcpH().getTh_seq();
			
			// 步骤2：SR协议关键：尝试缓存该包（支持乱序接收）
			int result = receiverWindow.bufferPacket(recvPack);
			
			// 步骤3：SR协议核心特性：独立确认
			// 只要收到校验和正确的包，都回复ACK（包括重复包）
			// 注意：即使是UNORDERED包，根据实验报告，也需要回复ACK
			if (result != ReceiverWindow.UNORDERED) {
				// 构造ACK报文，确认号等于收到的包的序列号
				tcpH.setTh_ack(seq);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				tcpH.setTh_eflag((byte)0);  // ACK包不模拟错误
				reply(ackPack);
				
				System.out.println("回复ACK - seq=" + seq);
			}
			
			// 步骤4：SR协议交付机制：尝试交付所有连续可交付的包
			// 这保证了向应用层交付的数据是按序的
			if (result == ReceiverWindow.IS_BASE) {
				// 只有当收到base位置的包时，才尝试交付
				TCP_PACKET deliverablePacket;
				while ((deliverablePacket = receiverWindow.getPacketToDeliver()) != null) {
					// 从缓冲区取出连续的包，加入交付队列
					dataQueue.add(deliverablePacket.getTcpS().getData());
				}
			}
			
		} else {
			// 数据损坏：SR协议直接丢弃，不回复ACK
			// 发送方的超时定时器会触发重传
			System.out.println("校验失败 - seq=" + recvPack.getTcpH().getTh_seq());
			System.out.println("计算值=" + CheckSum.computeChkSum(recvPack) + ", 接收值=" + recvPack.getTcpH().getTh_sum());
		}
		
		System.out.println();
		
		// 定期将累积的数据写入文件
		if (dataQueue.size() >= 20) {
			deliver_data();
		}
	}

	@Override
	//交付数据（将数据写入文件）；不需要修改
	public void deliver_data() {
		//检查dataQueue，将数据写入文件
		File fw = new File("recvData.txt");
		BufferedWriter writer;
		
		try {
			writer = new BufferedWriter(new FileWriter(fw, true));
			
			//循环检查data队列中是否有新交付数据
			while(!dataQueue.isEmpty()) {
				int[] data = dataQueue.poll();
				
				//将数据写入文件
				for(int i = 0; i < data.length; i++) {
					writer.write(data[i] + "\n");
				}
				
				writer.flush();		//清空输出缓存
			}
			writer.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}

	@Override
	//回复ACK报文段
	public void reply(TCP_PACKET replyPack) {
		//直接发送数据报，保留之前设置的eflag值
		client.send(replyPack);
	}
	
}
