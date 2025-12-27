/***************************GBN: 回退N步协议*****************/
/***** 标准GBN实现：接收方不缓存乱序包 ******************************/
package com.ouc.tcp.test;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import com.ouc.tcp.client.TCP_Receiver_ADT;
import com.ouc.tcp.message.*;

public class TCP_Receiver extends TCP_Receiver_ADT {
	
	private TCP_PACKET ackPack;	// ACK报文
	// GBN协议：接收方只维护期望序号，不需要窗口缓存
	private int expectedSeq;  // 期望接收的下一个序列号
	private int lastAckSeq;   // 上一个成功接收的序号（用于重发ACK）
	
	/*构造函数*/
	public TCP_Receiver() {
		super();
		super.initTCP_Receiver(this);
		// GBN协议初始化：从序列号0开始期望接收
		expectedSeq = 0;
		lastAckSeq = -1;  // 初始时没有已确认的包
		System.out.println("GBN协议接收端启动 - 期望序号=" + expectedSeq);
	}

	@Override
	// GBN协议接收方法：只接收期望序号的包，丢弃乱序包
	public void rdt_recv(TCP_PACKET recvPack) {
		// 步骤1：校验数据完整性
		if (CheckSum.computeChkSum(recvPack) == recvPack.getTcpH().getTh_sum()) {
			// 数据没有损坏
			int seq = recvPack.getTcpH().getTh_seq();
			
			// GBN核心逻辑：判断是否为期望的包
			if (seq == expectedSeq) {
				// ===== 情况1：收到期望的包 =====
				System.out.println("GBN接收 - seq=" + seq + " 是期望的包");
				
				// 交付数据到应用层
				dataQueue.add(recvPack.getTcpS().getData());
				
				// 回复ACK（累积确认）
				tcpH.setTh_ack(seq);
				ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
				tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
				tcpH.setTh_eflag((byte)0);  // ACK包不模拟错误
				reply(ackPack);
				
				System.out.println("回复ACK - seq=" + seq);
				
				// 更新期望序号和最后确认序号
				lastAckSeq = seq;
				expectedSeq++;
				System.out.println("GBN窗口移动 - 新期望序号=" + expectedSeq);
				
			} else {
				// ===== 情况2：收到乱序包或重复包 =====
				if (seq < expectedSeq) {
					// 重复包（已经交付过的包）
					System.out.println("GBN接收 - seq=" + seq + " 是重复包（期望=" + expectedSeq + "）");
				} else {
					// 乱序包（超前的包）- GBN直接丢弃
					System.out.println("GBN丢弃 - seq=" + seq + " 是乱序包（期望=" + expectedSeq + "）");
				}
				
				// GBN关键：重发最近一次成功接收的ACK
				if (lastAckSeq >= 0) {
					tcpH.setTh_ack(lastAckSeq);
					ackPack = new TCP_PACKET(tcpH, tcpS, recvPack.getSourceAddr());
					tcpH.setTh_sum(CheckSum.computeChkSum(ackPack));
					tcpH.setTh_eflag((byte)0);
					reply(ackPack);
					System.out.println("重发ACK - seq=" + lastAckSeq + " (期望=" + expectedSeq + ")");
				} else {
					System.out.println("未回复ACK - 因为还没有成功接收过任何包");
				}
			}
			
		} else {
			// 数据损坏：GBN直接丢弃，不回复ACK
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
